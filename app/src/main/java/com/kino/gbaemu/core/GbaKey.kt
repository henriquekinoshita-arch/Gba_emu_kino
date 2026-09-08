package com.kino.gbaemu.core

/**
 * Mirrors mGBA's `enum GBAKey` (include/mgba/internal/gba/input.h). The
 * ordinal is used directly as the bit index in the core's key bitmask, so
 * this order must not change without updating the native side too.
 */
enum class GbaKey {
    A,
    B,
    SELECT,
    START,
    RIGHT,
    LEFT,
    UP,
    DOWN,
    R,
    L,
}
