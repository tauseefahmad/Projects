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
        assertEquals(NavAction.SEARCH, action(KeyEvent.KEYCODE_F, ctrl = true))
        assertNull(action(KeyEvent.KEYCODE_F))
        assertEquals(NavAction.ROTATE, action(KeyEvent.KEYCODE_R, ctrl = true))
        assertNull(action(KeyEvent.KEYCODE_R))
        assertEquals(NavAction.FULL_SCREEN, action(KeyEvent.KEYCODE_F11))
        assertEquals(NavAction.EXIT_FULL_SCREEN, action(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun ctrlPlusMinusZeroZoom() {
        assertEquals(NavAction.ZOOM_IN, action(KeyEvent.KEYCODE_EQUALS, ctrl = true))
        assertEquals(NavAction.ZOOM_IN, action(KeyEvent.KEYCODE_PLUS, ctrl = true))
        assertEquals(NavAction.ZOOM_OUT, action(KeyEvent.KEYCODE_MINUS, ctrl = true))
        assertEquals(NavAction.ZOOM_FIT, action(KeyEvent.KEYCODE_0, ctrl = true))
        assertNull(action(KeyEvent.KEYCODE_MINUS))
        assertNull(action(KeyEvent.KEYCODE_0))
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
