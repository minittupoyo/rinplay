package net.minittu.rinplay.usb

/** `usb_iso.cpp` の JNI 窓口。ハンドルは Stream のポインタ。 */
object UsbIsoNative {
    init {
        System.loadLibrary("usbiso")
    }

    external fun create(fd: Int, epOut: Int, epFb: Int, maxPacket: Int, interval: Int, bytesPerFrame: Int, rate: Int): Long

    /** stop されるまで戻らない。呼んだスレッドで URB を回す。0 = 正常、負 = エラー終了。 */
    external fun run(handle: Long): Int
    external fun requestStop(handle: Long)
    external fun destroy(handle: Long)
    external fun write(handle: Long, data: ByteArray, off: Int, len: Int): Int
    external fun setPaused(handle: Long, paused: Boolean)
    external fun flush(handle: Long)
    external fun framesPlayed(handle: Long): Long
    external fun freeBytes(handle: Long): Int
    external fun underruns(handle: Long): Int

    /** claim で切り離したカーネルのドライバを付け直す。0 以上 = 成功、負 = -errno。 */
    external fun attachKernelDriver(fd: Int, interfaceNumber: Int): Int
}
