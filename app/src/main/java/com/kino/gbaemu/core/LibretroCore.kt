package com.kino.gbaemu.core

import java.nio.ByteBuffer

/**
 * Thin 1:1 wrapper over the JNI exports in core_bridge.c. Every function here
 * takes the opaque `handle` returned by [nativeLoadCore] and forwards straight
 * into the libretro core currently dlopen()'d behind it - there is no
 * per-platform branching on this side, by design: the whole point of going
 * through the libretro C ABI is that GBA and NDS look identical from here.
 */
object LibretroCore {
    init {
        System.loadLibrary("kino_bridge")
    }

    /** RETRO_DEVICE_ID_JOYPAD_* from libretro.h. */
    object Button {
        const val B = 0
        const val Y = 1
        const val SELECT = 2
        const val START = 3
        const val UP = 4
        const val DOWN = 5
        const val LEFT = 6
        const val RIGHT = 7
        const val A = 8
        const val X = 9
        const val L = 10
        const val R = 11
        const val L2 = 12
        const val R2 = 13
        const val L3 = 14
        const val R3 = 15
    }

    external fun nativeLoadCore(corePath: String): Long
    external fun nativeSetDirectories(handle: Long, systemDir: String, saveDir: String)
    external fun nativeLoadGame(handle: Long, romPath: String): Boolean
    external fun nativeUnloadGame(handle: Long)
    external fun nativeUnloadCore(handle: Long)

    external fun nativeRunFrame(handle: Long)
    external fun nativeReset(handle: Long)

    external fun nativeGetVideoBuffer(handle: Long): ByteBuffer?
    external fun nativeGetMaxWidth(handle: Long): Int
    external fun nativeGetMaxHeight(handle: Long): Int
    external fun nativeGetFrameWidth(handle: Long): Int
    external fun nativeGetFrameHeight(handle: Long): Int
    external fun nativeGetFps(handle: Long): Double
    external fun nativeGetSampleRate(handle: Long): Double

    external fun nativeSetButton(handle: Long, port: Int, id: Int, pressed: Boolean)
    external fun nativeSetPointer(handle: Long, x: Int, y: Int, pressed: Boolean)
    external fun nativePopAudio(handle: Long, out: ShortArray, maxFrames: Int): Int

    external fun nativeSerializeSize(handle: Long): Long
    external fun nativeSerialize(handle: Long): ByteArray?
    external fun nativeUnserialize(handle: Long, data: ByteArray): Boolean

    external fun nativeGetSaveRamSize(handle: Long): Long
    external fun nativeReadSaveRam(handle: Long): ByteArray?
    external fun nativeWriteSaveRam(handle: Long, data: ByteArray)
}
