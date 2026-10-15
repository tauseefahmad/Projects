package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {

    private val big = PageLink(0f, 0f, 100f, 100f, targetPage = 1)
    private val small = PageLink(10f, 10f, 20f, 20f, uri = "https://example.com")

    @Test
    fun hitFindsLinkUnderPoint() {
        assertEquals(big, Links.hit(listOf(big), 50f, 50f))
        assertNull(Links.hit(listOf(big), 150f, 50f))
    }

    @Test
    fun hitAllowsFingerSlop() {
        assertEquals(small, Links.hit(listOf(small), 22f, 15f))
        assertNull(Links.hit(listOf(small), 30f, 15f))
    }

    @Test
    fun smallestOverlappingLinkWins() {
        assertEquals(small, Links.hit(listOf(big, small), 15f, 15f))
    }

    @Test
    fun onlyWebMailAndPhoneLinksAreSafe() {
        assertTrue(Links.isSafeExternal("https://example.com"))
        assertTrue(Links.isSafeExternal("HTTP://example.com"))
        assertTrue(Links.isSafeExternal("mailto:a@b.de"))
        assertTrue(Links.isSafeExternal("tel:+4930123"))
        assertFalse(Links.isSafeExternal("javascript:alert(1)"))
        assertFalse(Links.isSafeExternal("file:///sdcard/x"))
        assertFalse(Links.isSafeExternal("intent://scan/#Intent;end"))
        assertFalse(Links.isSafeExternal("https:"))
        assertFalse(Links.isSafeExternal("no scheme"))
    }

    @Test
    fun bareWwwAddressesGetHttps() {
        assertEquals("https://www.example.com", Links.normalize("www.example.com"))
        assertEquals("mailto:a@b.de", Links.normalize(" mailto:a@b.de "))
    }
}
