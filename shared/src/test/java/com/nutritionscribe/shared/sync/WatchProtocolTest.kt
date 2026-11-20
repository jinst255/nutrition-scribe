package com.nutritionscribe.shared.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone and watch must read each other's messages exactly (relay untested on hardware). */
class WatchProtocolTest {
    @Test
    fun `requests, replies and state round-trip`() {
        val req = WatchProtocol.Request("a1", WatchProtocol.Kind.RESOLVE, text = "barcode 0 78742 05731 6", at = 1795000000000)
        assertEquals(req, WatchProtocol.decodeRequest(WatchProtocol.encode(req)))
        val reply = WatchProtocol.Reply("a1", WatchProtocol.Kind.HEARD, heard = "two eggs",
            lines = listOf(WatchProtocol.Line("Eggs", "2 large", 143), WatchProtocol.Line("Toast", "1 slice", 80)))
        val back = WatchProtocol.decodeReply(WatchProtocol.encode(reply))!!
        assertEquals(reply, back)
        assertEquals(223, back.kcal)
        val state = WatchState(goals = listOf(WatchState.Goal("Protein", 92.0, 160.0, "g", false)),
            need = WatchState.Need("Fiber", 9.0, 30.0, "g", "Oats covers 8 g."), debug = true, updatedAt = 5)
        assertEquals(state, WatchProtocol.decodeState(WatchProtocol.encode(state)))
        assertEquals(0.575f, state.goals.single().fraction, 1e-4f)
    }

    @Test
    fun `garbage and newer fields don't crash either side`() {
        assertNull(WatchProtocol.decodeRequest("not json".toByteArray()))
        assertNull(WatchProtocol.decodeState(null))
        val newer = """{"id":"x","kind":"heard","lines":[],"someday":"a new field"}"""
        assertEquals("x", WatchProtocol.decodeReply(newer.toByteArray())!!.id)
    }
}
