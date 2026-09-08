package com.kino.gbaemu.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.kino.gbaemu.core.GbaKey
import kotlin.math.abs

/** Sensible default mapping for a standard Android gamepad (Xbox/Switch-Pro-like layout). */
val DEFAULT_GAMEPAD_MAPPING: Map<Int, GbaKey> = mapOf(
    KeyEvent.KEYCODE_BUTTON_A to GbaKey.A,
    KeyEvent.KEYCODE_BUTTON_B to GbaKey.B,
    KeyEvent.KEYCODE_BUTTON_L1 to GbaKey.L,
    KeyEvent.KEYCODE_BUTTON_R1 to GbaKey.R,
    KeyEvent.KEYCODE_BUTTON_START to GbaKey.START,
    KeyEvent.KEYCODE_BUTTON_SELECT to GbaKey.SELECT,
    KeyEvent.KEYCODE_DPAD_UP to GbaKey.UP,
    KeyEvent.KEYCODE_DPAD_DOWN to GbaKey.DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT to GbaKey.LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT to GbaKey.RIGHT,
)

/** All GBA-mappable Android key codes, used by the remap-a-button settings screen. */
val REMAPPABLE_KEY_CODES: List<Int> = listOf(
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_B,
    KeyEvent.KEYCODE_BUTTON_X,
    KeyEvent.KEYCODE_BUTTON_Y,
    KeyEvent.KEYCODE_BUTTON_L1,
    KeyEvent.KEYCODE_BUTTON_R1,
    KeyEvent.KEYCODE_BUTTON_L2,
    KeyEvent.KEYCODE_BUTTON_R2,
    KeyEvent.KEYCODE_BUTTON_START,
    KeyEvent.KEYCODE_BUTTON_SELECT,
    KeyEvent.KEYCODE_BUTTON_THUMBL,
    KeyEvent.KEYCODE_BUTTON_THUMBR,
)

/**
 * Translates KeyEvents/MotionEvents from Bluetooth/USB gamepads into GBA
 * key presses. Meant to be driven from an Activity's
 * dispatchKeyEvent/dispatchGenericMotionEvent overrides, since Compose has
 * no first-class joystick-axis API.
 */
class GamepadInputHandler(
    private var mapping: Map<Int, GbaKey>,
    private val onKey: (GbaKey, Boolean) -> Unit,
) {
    private var stickLeft = false
    private var stickRight = false
    private var stickUp = false
    private var stickDown = false

    fun updateMapping(newMapping: Map<Int, GbaKey>) {
        mapping = newMapping
    }

    fun isFromGamepad(event: KeyEvent): Boolean =
        isGamepadSource(event.source) && event.repeatCount == 0

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val gbaKey = mapping[event.keyCode] ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> onKey(gbaKey, true)
            KeyEvent.ACTION_UP -> onKey(gbaKey, false)
            else -> return false
        }
        return true
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (!isGamepadSource(event.source)) return false

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val axisX = event.getAxisValue(MotionEvent.AXIS_X)
        val axisY = event.getAxisValue(MotionEvent.AXIS_Y)

        val x = if (abs(hatX) > DEAD_ZONE) hatX else axisX
        val y = if (abs(hatY) > DEAD_ZONE) hatY else axisY

        setDirection(x < -DEAD_ZONE, x > DEAD_ZONE, y < -DEAD_ZONE, y > DEAD_ZONE)
        return true
    }

    private fun setDirection(left: Boolean, right: Boolean, up: Boolean, down: Boolean) {
        if (left != stickLeft) {
            stickLeft = left
            onKey(GbaKey.LEFT, left)
        }
        if (right != stickRight) {
            stickRight = right
            onKey(GbaKey.RIGHT, right)
        }
        if (up != stickUp) {
            stickUp = up
            onKey(GbaKey.UP, up)
        }
        if (down != stickDown) {
            stickDown = down
            onKey(GbaKey.DOWN, down)
        }
    }

    companion object {
        private const val DEAD_ZONE = 0.5f

        fun isGamepadSource(source: Int): Boolean =
            (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }
}
