package com.rtbishop.look4sat.core.presentation

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationBackStackTest {
    @Test
    fun rapidSatelliteConfirmationKeepsThePassesRoot() {
        val stack = mutableListOf<NavKey>(Screen.Passes, Screen.Satellites)
        val confirm = { stack.popCurrentEntry(Screen.Satellites) }

        assertTrue(confirm())
        repeat(10) { assertFalse(confirm()) }

        assertEquals(listOf(Screen.Passes), stack)
    }

    @Test
    fun staleReturnDoesNotPopThePageUnderTheOutgoingEntry() {
        val stack = mutableListOf<NavKey>(Screen.Passes, Screen.Map, Screen.Satellites)

        assertTrue(stack.popCurrentEntry(Screen.Satellites))
        assertFalse(stack.popCurrentEntry(Screen.Satellites))

        assertEquals(listOf(Screen.Passes, Screen.Map), stack)
        assertTrue(stack.popCurrentEntry(Screen.Map))
        assertEquals(listOf(Screen.Passes), stack)
    }

    @Test
    fun rootReturnIsIgnoredEvenWhenTheExpectedEntryMatches() {
        val stack = mutableListOf<NavKey>(Screen.Passes)

        assertFalse(stack.popCurrentEntry(Screen.Passes))

        assertEquals(listOf(Screen.Passes), stack)
    }

    @Test
    fun oldCallbackCannotCloseANewerDestination() {
        val stack = mutableListOf<NavKey>(Screen.Passes, Screen.Satellites, Screen.AMSAT)

        assertFalse(stack.popCurrentEntry(Screen.Satellites))

        assertEquals(listOf(Screen.Passes, Screen.Satellites, Screen.AMSAT), stack)
    }

    @Test
    fun repeatedGridPickerCompletionDoesNotLeaveTheMatchPage() {
        val picker = MatchGridPickerDestination(stationA = true)
        val stack = mutableListOf<NavKey>(Screen.Passes, Screen.Mutual, picker)
        var scrollRestores = 0
        repeat(2) {
            if (stack.popCurrentEntry(picker)) scrollRestores++
        }

        assertEquals(1, scrollRestores)
        assertEquals(listOf(Screen.Passes, Screen.Mutual), stack)
    }

    @Test
    fun repeatedRootOverlayReturnKeepsTheMainScreen() {
        val stack = mutableListOf<NavKey>(Screen.Passes, RadarDestination)

        assertTrue(stack.popCurrentEntry(RadarDestination))
        assertFalse(stack.popCurrentEntry(RadarDestination))

        assertEquals(listOf(Screen.Passes), stack)
    }

    @Test
    fun missingExpectedEntryDoesNotPopAnything() {
        val stack = mutableListOf<NavKey>(Screen.Passes, Screen.Satellites)

        assertFalse(stack.popCurrentEntry(null))
        assertFalse(mutableListOf<NavKey>().popCurrentEntry(Screen.Passes))

        assertEquals(listOf(Screen.Passes, Screen.Satellites), stack)
    }
}
