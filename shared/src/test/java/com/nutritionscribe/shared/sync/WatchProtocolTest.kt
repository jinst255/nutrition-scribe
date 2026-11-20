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

    @Test
    fun `the shortcut fields round-trip, and an older phone's state still reads`() {
        val state = WatchState(
            need = WatchState.Need("Protein", 92.0, 160.0, "g", foods = listOf(WatchState.Fill("e1", "Protein shake", 30.0, 160))),
            again = listOf(WatchState.Again("e2", "Coffee", "1 cup", 5, hour = 8.0)),
            meals = listOf(WatchState.Meal(listOf("e3"), "Your usual lunch", listOf("Burrito"), 400, hour = 12.5)),
            home = WatchState.Home.ONE, one = WatchState.Goal("Fiber", 9.0, 30.0, "g", false), lastAt = 77, toCheck = 2,
        )
        assertEquals(state, WatchProtocol.decodeState(WatchProtocol.encode(state)))
        // 11-19's phone: no foods, hours, home, lastAt or toCheck.
        val old = """{"goals":[],"need":{"name":"Fiber","value":9,"target":30,"unit":"g"},"again":[{"id":"a","name":"Coffee","sub":"1 cup"}],"meals":[{"ids":["b"],"label":"L","names":["x"],"kcal":1}]}"""
        val read = WatchProtocol.decodeState(old)!!
        assertEquals(emptyList<WatchState.Fill>(), read.need!!.foods)
        assertNull(read.again.single().hour)
        assertNull(read.home)
        assertEquals(0, read.toCheck)
    }

    @Test
    fun `a log from the check screen says it was seen, a kept one does not`() {
        val seen = WatchProtocol.Request("a", WatchProtocol.Kind.LOG, "eggs", 1, seen = true)
        assertEquals(true, WatchProtocol.decodeRequest(WatchProtocol.encode(seen))!!.seen)
        // An older watch never sends it: treated as not seen, so it waits on the phone.
        assertEquals(false, WatchProtocol.decodeRequest("""{"id":"a","kind":"log","text":"eggs","at":1}""".toByteArray())!!.seen)
    }
}
