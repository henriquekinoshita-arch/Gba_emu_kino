package com.kino.gbaemu.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Result of [MgbaCore.loadRom]. */
enum class RomLoadResult {
    SUCCESS,
    CANNOT_OPEN_FILE,
    UNSUPPORTED_ROM,
    CORE_NOT_READY,
}

/**
 * Thin, mostly 1:1 wrapper around the native JNI bridge in
 * app/src/main/cpp/mgba_jni.c. Callers should generally use
 * [com.kino.gbaemu.core.EmulatorEngine] instead of this class directly,
 * since correct usage requires careful thread discipline (see the frame
 * pacing notes on [beginFrame]/[endFrame]).
 *
 * Not thread-safe beyond what the underlying mCoreThread already
 * guarantees: [beginFrame]/[endFrame] are meant to be called only from the
 * render thread, [popAudio] only from the audio thread, and lifecycle /
 * input calls from any single thread (typically the main thread).
 */
class MgbaCore {
    companion object {
        init {
            System.loadLibrary("kinogba")
        }

        const val GBA_WIDTH = 240
        const val GBA_HEIGHT = 160
        const val VIDEO_BUFFER_BYTES = GBA_WIDTH * GBA_HEIGHT * 4
    }

    /** Direct, native-order buffer the core renders into (ARGB_8888-compatible). */
    val videoBuffer: ByteBuffer = ByteBuffer.allocateDirect(VIDEO_BUFFER_BYTES)
        .order(ByteOrder.nativeOrder())

    private var handle: Long = 0L

    val isCreated: Boolean get() = handle != 0L

    /**
     * @param crashLogPath when non-null, the native side appends a plain-text
     * breadcrumb line before/after each risky step (core init, ROM load,
     * thread start, cheat device access). A native crash (SIGSEGV/abort)
     * kills the process before any Java exception handler or logcat access
     * could help, so this file - readable by the app itself, no adb needed -
     * is what lets [com.kino.gbaemu.data.CrashLog] show the last thing that
     * happened right before such a crash.
     */
    fun create(crashLogPath: String? = null): Boolean {
        if (isCreated) return true
        handle = nativeCreate(videoBuffer, crashLogPath)
        return isCreated
    }

    fun loadRom(romPath: String, savePath: String): RomLoadResult {
        if (!isCreated) return RomLoadResult.CORE_NOT_READY
        return when (nativeLoadRom(handle, romPath, savePath)) {
            0 -> RomLoadResult.SUCCESS
            1 -> RomLoadResult.CANNOT_OPEN_FILE
            else -> RomLoadResult.UNSUPPORTED_ROM
        }
    }

    fun start(): Boolean = isCreated && nativeStart(handle)

    fun stop() {
        if (isCreated) nativeStop(handle)
    }

    fun destroy() {
        if (isCreated) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    fun pause() {
        if (isCreated) nativePause(handle)
    }

    fun unpause() {
        if (isCreated) nativeUnpause(handle)
    }

    fun isPaused(): Boolean = isCreated && nativeIsPaused(handle)

    fun reset() {
        if (isCreated) nativeReset(handle)
    }

    fun setRewinding(rewinding: Boolean) {
        if (isCreated) nativeSetRewinding(handle, rewinding)
    }

    fun setKey(key: GbaKey, pressed: Boolean) {
        if (isCreated) nativeSetKey(handle, key.ordinal, pressed)
    }

    /**
     * Blocks the calling thread until mGBA's core thread reaches the next
     * vblank (paced to the console's real ~59.7275 fps by core->opts
     * .videoSync), then returns true and leaves [videoBuffer] holding that
     * frame's pixels until [endFrame] is called. Must be paired with a
     * prompt [endFrame] call - the core thread is blocked in between.
     */
    fun beginFrame(): Boolean = isCreated && nativeBeginFrame(handle)

    fun endFrame() {
        if (isCreated) nativeEndFrame(handle)
    }

    fun getSampleRate(): Int = if (isCreated) nativeGetSampleRate(handle) else 32768

    /** Copies up to [out].size stereo frames into [out]; returns frames copied. */
    fun popAudio(out: ShortArray): Int = if (isCreated) nativePopAudio(handle, out, out.size / 2) else 0

    fun saveStateBytes(): ByteArray? = if (isCreated) nativeSaveStateBytes(handle) else null

    fun loadStateBytes(data: ByteArray): Boolean = isCreated && nativeLoadStateBytes(handle, data)

    fun cheatsClear() {
        if (isCreated) nativeCheatsClear(handle)
    }

    fun cheatsAdd(name: String, code: String): Boolean = isCreated && nativeCheatsAdd(handle, name, code)

    private external fun nativeCreate(videoBuffer: ByteBuffer, crashLogPath: String?): Long
    private external fun nativeLoadRom(handle: Long, romPath: String, savePath: String): Int
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativePause(handle: Long)
    private external fun nativeUnpause(handle: Long)
    private external fun nativeIsPaused(handle: Long): Boolean
    private external fun nativeReset(handle: Long)
    private external fun nativeSetRewinding(handle: Long, rewinding: Boolean)
    private external fun nativeSetKey(handle: Long, keyIndex: Int, pressed: Boolean)
    private external fun nativeBeginFrame(handle: Long): Boolean
    private external fun nativeEndFrame(handle: Long)
    private external fun nativeGetSampleRate(handle: Long): Int
    private external fun nativePopAudio(handle: Long, out: ShortArray, maxFrames: Int): Int
    private external fun nativeSaveStateBytes(handle: Long): ByteArray?
    private external fun nativeLoadStateBytes(handle: Long, data: ByteArray): Boolean
    private external fun nativeCheatsClear(handle: Long)
    private external fun nativeCheatsAdd(handle: Long, name: String, code: String): Boolean
}
