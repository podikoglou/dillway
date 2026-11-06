package com.dillway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SaveArgumentsTest {

    @Test
    fun `no optional words keeps the current selection`() {
        assertEquals(SaveArguments(label = null, confirmed = false), SaveArguments.parse(null, null))
    }

    @Test
    fun `one word is a label`() {
        assertEquals(
            SaveArguments(label = "speed", confirmed = false),
            SaveArguments.parse("speed", null),
        )
    }

    @Test
    fun `one confirm word is the old shortcut`() {
        assertEquals(
            SaveArguments(label = null, confirmed = true),
            SaveArguments.parse("confirm", null),
        )
    }

    @Test
    fun `a label followed by confirm overwrites`() {
        assertEquals(
            SaveArguments(label = "speed", confirmed = true),
            SaveArguments.parse("speed", "confirm"),
        )
    }

    @Test
    fun `confirm before a label still parses`() {
        assertEquals(
            SaveArguments(label = "speed", confirmed = true),
            SaveArguments.parse("confirm", "speed"),
        )
    }

    @Test
    fun `confirm is case insensitive`() {
        assertEquals(
            SaveArguments(label = null, confirmed = true),
            SaveArguments.parse("CONFIRM", null),
        )
    }

    @Test
    fun `two labels are ambiguous`() {
        assertNull(SaveArguments.parse("speed", "legit"))
    }
}
