package com.streamify.app.data

object NativeBridge {
    init {
        try {
            System.loadLibrary("streamify_core_rs")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    private const val BUFFER_SIZE = 256

    fun getSapisidHash(sapisid: String, origin: String = "https://music.youtube.com"): String? {
        val sapisidBytes = sapisid.toByteArray(Charsets.UTF_8)
        val originBytes = origin.toByteArray(Charsets.UTF_8)
        val outBuffer = ByteArray(BUFFER_SIZE)
        val written = try {
            nativeGenerateSapisidHash(
                sapisidBytes, sapisidBytes.size,
                originBytes, originBytes.size,
                outBuffer, outBuffer.size
            )
        } catch (e: UnsatisfiedLinkError) {
            -1
        }
        return if (written > 0) String(outBuffer, 0, written, Charsets.UTF_8) else null
    }

    external fun nativeGenerateCadId(title: String, artist: String, durationSec: Int): String

    private external fun nativeGenerateSapisidHash(
        sapisidBytes: ByteArray,
        sapisidLen: Int,
        originBytes: ByteArray,
        originLen: Int,
        outBuf: ByteArray,
        outBufLen: Int
    ): Int
}
