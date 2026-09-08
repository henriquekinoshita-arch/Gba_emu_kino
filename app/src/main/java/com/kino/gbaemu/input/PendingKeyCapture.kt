package com.kino.gbaemu.input

/**
 * Tiny bridge between an Activity's raw dispatchKeyEvent() override (where
 * physical gamepad KeyEvents actually arrive - Compose has no first-class
 * API for them) and the controller-remapping screen, which just wants "the
 * next physical button the user presses".
 */
object PendingKeyCapture {
    @Volatile var onKeyDown: ((Int) -> Unit)? = null

    fun consume(keyCode: Int): Boolean {
        val callback = onKeyDown ?: return false
        onKeyDown = null
        callback(keyCode)
        return true
    }
}
