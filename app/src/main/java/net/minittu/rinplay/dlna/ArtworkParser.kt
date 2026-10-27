package net.minittu.rinplay.dlna

/**
 * 音声ファイルの中の埋め込みジャケット(FLAC の PICTURE ブロック、MP3 の ID3v2 の APIC / PIC)を探す。
 *
 * ファイル全体を持ってこないために、先頭の一部だけで「絵がどこにあるか」を判定し、
 * そこが範囲外なら、取りに行くべき範囲を [Probe.Need] で返す。呼び出し側はその範囲だけを取り直す。
 */
internal object ArtworkParser {

    /** 探した結果。 */
    internal sealed interface Probe {
        /** 先頭の中に絵があった。 */
        class Found(val image: ByteArray) : Probe

        /** [start]..[endInclusive] を取り直せば [parse] で読める。 */
        class Need(val start: Long, val endInclusive: Long, val parse: (ByteArray) -> ByteArray?) : Probe

        /** 埋め込みジャケットは無い(対応していない形式も含む)。 */
        data object None : Probe
    }

    /** 先頭の [prefix] から探す。 */
    fun probe(prefix: ByteArray): Probe = when {
        prefix.startsWith("fLaC") -> probeFlac(prefix)
        prefix.startsWith("ID3") -> probeId3(prefix)
        else -> Probe.None
    }

    // --- FLAC ---

    /** メタデータブロックをたどり、PICTURE(種別 6)を探す。 */
    private fun probeFlac(data: ByteArray): Probe {
        var pos = 4 // "fLaC"
        while (pos + 4 <= data.size) {
            val header = data[pos].toInt() and 0xFF
            val isLast = header and 0x80 != 0
            val type = header and 0x7F
            val size = readInt24(data, pos + 1)
            if (size < 0) return Probe.None
            if (type == FLAC_PICTURE) {
                val start = pos + 4L
                val end = start + size - 1
                // ブロック全体が先頭に入っていれば、そのまま読める。
                return if (end < data.size) {
                    parseFlacPicture(data, pos + 4, size)?.let { Probe.Found(it) } ?: Probe.None
                } else {
                    Probe.Need(start, end) { parseFlacPicture(it, 0, it.size) }
                }
            }
            if (isLast) return Probe.None
            pos += 4 + size
        }
        return Probe.None
    }

    /**
     * PICTURE ブロックの中身から画像だけを取り出す。
     * 形式: 種別(4) / MIME 長(4) + MIME / 説明長(4) + 説明 / 幅・高さ・色深度・色数(4×4) / データ長(4) / 画像。
     */
    private fun parseFlacPicture(block: ByteArray, offset: Int, size: Int): ByteArray? {
        var pos = offset
        val end = offset + size
        if (pos + 8 > end) return null
        pos += 4 // 画像の種別(表紙 / 裏表紙など)
        val mimeLength = readInt32(block, pos).also { if (it < 0) return null }
        pos += 4
        if (pos + mimeLength > end) return null
        val mime = String(block, pos, mimeLength, Charsets.US_ASCII)
        pos += mimeLength
        if (pos + 4 > end) return null
        val descriptionLength = readInt32(block, pos).also { if (it < 0) return null }
        pos += 4
        if (pos + descriptionLength > end) return null
        pos += descriptionLength
        if (pos + 20 > end) return null
        pos += 16 // 幅・高さ・色深度・色数
        val dataLength = readInt32(block, pos).also { if (it < 0) return null }
        pos += 4
        if (pos + dataLength > end) return null
        if (!mime.startsWith("image/")) return null
        return imageAt(block, pos, dataLength)
    }

    // --- ID3v2(MP3 など) ---

    private fun probeId3(data: ByteArray): Probe {
        if (data.size < 10) return Probe.None
        val major = data[3].toInt() and 0xFF
        if (major !in 2..4) return Probe.None
        val flags = data[5].toInt() and 0xFF
        val tagSize = readSynchsafe(data, 6)
        if (tagSize < 0) return Probe.None
        val tagEnd = 10L + tagSize

        var pos = 10
        if (flags and FLAG_EXTENDED_HEADER != 0) {
            if (pos + 4 > data.size) return Probe.None
            // v2.4 は拡張ヘッダ自身を含む大きさ、v2.3 は含まない。
            val extended = if (major >= 4) readSynchsafe(data, pos) else readInt32(data, pos) + 4
            if (extended < 0) return Probe.None
            pos += extended
        }

        val idLength = if (major == 2) 3 else 4
        val frameHeader = if (major == 2) 6 else 10
        while (pos + frameHeader <= data.size && pos.toLong() < tagEnd) {
            val id = String(data, pos, idLength, Charsets.US_ASCII)
            if (id[0] == '\u0000') break // 以降は空き
            val size = when {
                major == 2 -> readInt24(data, pos + 3)
                major == 4 -> readSynchsafe(data, pos + 4)
                else -> readInt32(data, pos + 4)
            }
            // タグの大きさが実際より小さく書かれているファイルがあるので、フレームの「始まり」だけを見る。
            if (size <= 0 || pos + frameHeader > tagEnd) break
            val body = pos + frameHeader
            if (id == "APIC" || id == "PIC") {
                val start = body.toLong()
                val end = start + size - 1
                return if (end < data.size) {
                    parseId3Picture(data, body, size, major)?.let { Probe.Found(it) } ?: Probe.None
                } else {
                    Probe.Need(start, end) { parseId3Picture(it, 0, it.size, major) }
                }
            }
            pos = body + size
        }
        return Probe.None
    }

    /**
     * APIC(v2.3 / v2.4)または PIC(v2.2)のフレームから画像だけを取り出す。
     * 形式: エンコーディング(1) / MIME(v2.2 は "JPG" などの 3 文字)/ 画像種別(1) / 説明(終端はエンコーディング次第) / 画像。
     */
    private fun parseId3Picture(frame: ByteArray, offset: Int, size: Int, major: Int): ByteArray? {
        var pos = offset
        val end = offset + size
        if (pos >= end) return null
        val encoding = frame[pos].toInt() and 0xFF
        pos += 1
        if (major == 2) {
            pos += 3 // "JPG" / "PNG"
        } else {
            while (pos < end && frame[pos].toInt() != 0) pos++
            pos++ // MIME の終端
        }
        pos += 1 // 画像種別
        if (pos > end) return null
        // 説明文の終端。UTF-16 のときは 2 バイトの 0。
        if (encoding == ENCODING_UTF16 || encoding == ENCODING_UTF16_BE) {
            while (pos + 1 < end && !(frame[pos].toInt() == 0 && frame[pos + 1].toInt() == 0)) pos += 2
            pos += 2
        } else {
            while (pos < end && frame[pos].toInt() != 0) pos++
            pos++
        }
        if (pos >= end) return null
        return imageAt(frame, pos, end - pos)
    }

    // --- 共通 ---

    /** 画像として使えるバイト列かどうかを確かめて、その範囲を切り出す。 */
    private fun imageAt(data: ByteArray, offset: Int, length: Int): ByteArray? {
        if (length <= 0 || offset < 0 || offset + length > data.size) return null
        val image = data.copyOfRange(offset, offset + length)
        val isJpeg = image.size > 2 && image[0] == 0xFF.toByte() && image[1] == 0xD8.toByte()
        val isPng = image.size > 7 && image[0] == 0x89.toByte() && image[1] == 'P'.code.toByte() && image[2] == 'N'.code.toByte()
        return if (isJpeg || isPng) image else null
    }

    private fun ByteArray.startsWith(tag: String): Boolean =
        size >= tag.length && tag.indices.all { this[it] == tag[it].code.toByte() }

    /** 24bit のビッグエンディアン。範囲外なら -1。 */
    private fun readInt24(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 3 > data.size) return -1
        return ((data[offset].toInt() and 0xFF) shl 16) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            (data[offset + 2].toInt() and 0xFF)
    }

    /** 32bit のビッグエンディアン。範囲外、または負の値なら -1(ID3 の大きさは符号なしだが、実用上これで足りる)。 */
    private fun readInt32(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > data.size) return -1
        val value = ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
        return if (value < 0) -1 else value
    }

    /** ID3v2.4 の大きさ(各バイトの最上位ビットを使わない 7bit×4)。 */
    private fun readSynchsafe(data: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > data.size) return -1
        val value = ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)
        return if (value < 0) -1 else value
    }

    private const val FLAC_PICTURE = 6
    private const val FLAG_EXTENDED_HEADER = 0x40
    private const val ENCODING_UTF16 = 1
    private const val ENCODING_UTF16_BE = 2
}
