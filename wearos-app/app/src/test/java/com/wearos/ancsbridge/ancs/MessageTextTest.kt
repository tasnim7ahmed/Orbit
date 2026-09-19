package com.wearos.ancsbridge.ancs

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTextTest {

    @Test
    fun `removes preheader padding seen in email previews`() {
        // Patterns captured from real iPhone Mail notifications
        val cgjRun = " ͏".repeat(40)
        assertEquals(
            "Watch your transfer move around the world with status updates through delivery.",
            MessageText.clean("Watch your transfer move around the world with status updates through delivery.$cgjRun")
        )
        val pairRun = " ͏‌".repeat(30)
        assertEquals("Statement is now available.", MessageText.clean("Statement is now available.$pairRun"))
        val mixedRun = " ͏ ‌ ﻿".repeat(10)
        assertEquals("Hello", MessageText.clean("Hello$mixedRun"))
    }

    @Test
    fun `padding in the middle becomes one space`() {
        assertEquals("Sale ends today Shop now", MessageText.clean("Sale ends today ͏ ͏ ͏ Shop now"))
    }

    @Test
    fun `keeps a single zero-width non-joiner inside words`() {
        val bengali = "র‌য়াব"
        assertEquals(bengali, MessageText.clean(bengali))
        val persian = "می‌خواهم"
        assertEquals(persian, MessageText.clean(persian))
    }

    @Test
    fun `leaves ordinary text alone`() {
        val text = "Line one\n\nLine two   with spaces  \n"
        assertEquals(text, MessageText.clean(text))
        assertEquals("", MessageText.clean(""))
        // Emoji sequences use the zero-width joiner, which is not filler
        val family = "👨‍👩‍👧"
        assertEquals(family, MessageText.clean(family))
    }
}
