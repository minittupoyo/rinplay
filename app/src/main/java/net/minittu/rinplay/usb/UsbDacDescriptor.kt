package net.minittu.rinplay.usb

/** UAC2 の AudioStreaming 代替設定 1 つ分(PCM 出力)。 */
data class AsAlt(
    val interfaceNumber: Int,
    val altSetting: Int,
    val channels: Int,
    val subslotSize: Int,
    val bitResolution: Int,
    val endpointAddress: Int,
    val maxPacketSize: Int,
    val interval: Int,
    val feedbackEndpoint: Int,
    val feedbackMaxPacket: Int,
    val pcm: Boolean,
)

data class UacDescriptor(
    val acInterface: Int,
    val clockSourceId: Int,
    val alts: List<AsAlt>,
    /** Feature Unit の ID(なければ -1)と、VOLUME を持つチャンネル(0 = マスター)。 */
    val featureUnitId: Int = -1,
    val volumeChannels: List<Int> = emptyList(),
)

/** rawDescriptors から UAC2 の出力ストリーム構成を取り出す。UAC2 でなければ null。 */
fun parseUac2(raw: ByteArray): UacDescriptor? {
    var acInterface = -1
    var clockId = -1
    var featureUnit = -1
    val volumeChannels = mutableListOf<Int>()
    val alts = mutableListOf<AsAlt>()

    var curIf = -1
    var curAlt = 0
    var curSubclass = 0
    var curProtocol = 0
    var channels = 0
    var pcm = false
    var subslot = 0
    var bits = 0
    var outEp = 0
    var outMax = 0
    var outInterval = 0
    var fbEp = 0
    var fbMax = 0
    var open = false

    fun flush() {
        if (open && outEp != 0 && curSubclass == 2) {
            alts += AsAlt(curIf, curAlt, channels, subslot, bits, outEp, outMax, outInterval, fbEp, fbMax, pcm)
        }
        open = false
    }

    var p = 0
    while (p + 2 <= raw.size) {
        val len = raw[p].toInt() and 0xFF
        if (len < 2 || p + len > raw.size) break
        val type = raw[p + 1].toInt() and 0xFF
        fun u8(i: Int) = raw[p + i].toInt() and 0xFF
        when (type) {
            0x04 -> { // INTERFACE
                flush()
                curIf = u8(2); curAlt = u8(3)
                val cls = u8(5); curSubclass = u8(6); curProtocol = u8(7)
                open = cls == 1 && curProtocol == 0x20
                channels = 0; pcm = false; subslot = 0; bits = 0
                outEp = 0; outMax = 0; outInterval = 0; fbEp = 0; fbMax = 0
                if (open && curSubclass == 1) acInterface = curIf
            }
            0x24 -> if (open) {
                val sub = u8(2)
                if (curSubclass == 1 && sub == 0x0A && clockId < 0) clockId = u8(3)
                if (curSubclass == 1 && sub == 0x06 && featureUnit < 0) {
                    // FEATURE_UNIT: bUnitID, bSourceID, bmaControls[0=マスター, 1.. = 各チャンネル](各 4 バイト)。
                    // VOLUME は bit2-3(3 = 読み書き可)。
                    featureUnit = u8(3)
                    var ch = 0
                    while (5 + ch * 4 + 4 <= len) {
                        if (((u8(5 + ch * 4) shr 2) and 3) == 3) volumeChannels += ch
                        ch++
                    }
                }
                if (curSubclass == 2 && sub == 0x01) {
                    channels = u8(10)
                    pcm = (u8(6) and 1) != 0 // bmFormats bit0 = PCM
                }
                if (curSubclass == 2 && sub == 0x02) {
                    subslot = u8(4); bits = u8(5)
                }
            }
            0x05 -> if (open && curSubclass == 2) {
                val addr = u8(2)
                val attr = u8(3)
                val max = (u8(4) or (u8(5) shl 8))
                val packet = (max and 0x7FF) * (1 + ((max shr 11) and 3))
                val isOut = (addr and 0x80) == 0
                val isoType = (attr and 3) == 1
                val usage = (attr shr 4) and 3
                if (isoType && isOut && usage == 0) {
                    outEp = addr; outMax = packet; outInterval = u8(6)
                } else if (isoType && !isOut) {
                    fbEp = addr; fbMax = packet
                }
            }
        }
        p += len
    }
    flush()
    if (acInterface < 0 || clockId < 0 || alts.isEmpty()) return null
    return UacDescriptor(acInterface, clockId, alts, featureUnit, volumeChannels)
}
