package com.example.pdfreader

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyNavigationTest {

    private fun action(key: Int, ctrl: Boolean = false, shift: Boolean = false, volume: Boolean = false) =
        KeyNavigation.actionFor(key, ctrl, shift, volume)

    @Test
    fun pageKeysTurnPages() {
        assertEquals(NavAction.NEXT, action(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(NavAction.PREVIOUS, action(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(NavAction.NEXT, action(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(NavAction.PREVIOUS, action(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(NavAction.NEXT, action(KeyEvent.KEYCODE_J))
        assertEquals(NavAction.PREVIOUS, action(KeyEvent.KEYCODE_K))
    }

    @Test
    fun spaceGoesForwardAndShiftSpaceBack() {
        assertEquals(NavAction.NEXT, action(KeyEvent.KEYCODE_SPACE))
        assertEquals(NavAction.PREVIOUS, action(KeyEvent.KEYCODE_SPACE, shift = true))
    }

    @Test
    fun homeEndAndCtrlG() {
        assertEquals(NavAction.FIRST, action(KeyEvent.KEYCODE_MOVE_HOME))
        assertEquals(NavAction.LAST, action(KeyEvent.KEYCODE_MOVE_END))
        assertEquals(NavAction.GO_TO, action(KeyEvent.KEYCODE_G, ctrl = true))
        assertNull(action(KeyEvent.KEYCODE_G))
    }

    @Test
    fun volumeKeysOnlyWhenEnabled() {
        assertNull(action(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertNull(action(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(NavAction.NEXT, action(KeyEvent.KEYCODE_VOLUME_DOWN, volume = true))
        assertEquals(NavAction.PREVIOUS, action(KeyEvent.KEYCODE_VOLUME_UP, volume = true))
    }

    @Test
    fun otherKeysAreIgnored() {
        assertNull(action(KeyEvent.KEYCODE_A))
        assertNull(action(KeyEvent.KEYCODE_ENTER))
    }
}
