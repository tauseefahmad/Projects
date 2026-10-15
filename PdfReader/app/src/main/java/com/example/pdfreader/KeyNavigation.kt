package com.example.pdfreader

import android.view.KeyEvent

enum class NavAction { NEXT, PREVIOUS, FIRST, LAST, GO_TO }

/** Keyboard and volume-key shortcuts for tablets, Chromebooks and phones with keyboards. */
object KeyNavigation {

    fun actionFor(keyCode: Int, ctrl: Boolean, shift: Boolean, volumeKeys: Boolean): NavAction? = when (keyCode) {
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_J -> NavAction.NEXT
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_K -> NavAction.PREVIOUS
        KeyEvent.KEYCODE_SPACE -> if (shift) NavAction.PREVIOUS else NavAction.NEXT
        KeyEvent.KEYCODE_MOVE_HOME -> NavAction.FIRST
        KeyEvent.KEYCODE_MOVE_END -> NavAction.LAST
        KeyEvent.KEYCODE_G -> if (ctrl) NavAction.GO_TO else null
        KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeys) NavAction.NEXT else null
        KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeys) NavAction.PREVIOUS else null
        else -> null
    }
}
