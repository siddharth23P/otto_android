package dev.otto.phone

import dev.otto.phone.access.EventLog
import dev.otto.phone.access.Kind
import dev.otto.phone.access.SettlePolicy
import dev.otto.phone.access.Settler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettleTest {
    /** A clock that only the sleeper moves, delivering the scripted events as their time comes. */
    private class Rig(events: List<Pair<Long, Kind>> = emptyList(), private val pkg: String = "com.example.shop") {
        var now = 0L
        val log = EventLog()
        private val pending = events.sortedBy { it.first }.toMutableList()
        val sleeps = mutableListOf<Long>()
        var cap = Long.MAX_VALUE

        fun deliver() { while (pending.isNotEmpty() && pending.first().first <= now) pending.removeAt(0).let { (at, kind) -> log.record(kind, pkg, at) } }

        val settler = Settler({ now }, { ms ->
            assertTrue("asked to sleep $ms at $now past the cap $cap", now + ms <= cap)
            sleeps += ms; now += ms; deliver()
        }, log)

        fun await(policy: SettlePolicy = SettlePolicy.ACTION, since: Long = 0, actedAt: Long = since, until: (() -> Boolean)? = null) =
            settler.await(since, actedAt, policy.also { cap = since + it.capMs }, until)
    }

    @Test fun aScreenThatSaysNothingIsReadAfterTheQuietWindow() {
        val rig = Rig()
        val settle = rig.await()
        assertTrue(settle.settled)
        assertEquals(200, rig.now)
        assertEquals(200, settle.waitedMs)
    }

    @Test fun contentStillChangingIsWaitedOut() {
        val rig = Rig((0L..600L step 50).map { it to Kind.CONTENT })
        val settle = rig.await()
        assertTrue(settle.settled)
        assertEquals(800, rig.now)
    }

    @Test fun aNewWindowIsGivenTimeToFillIn() {
        val rig = Rig(listOf(100L to Kind.STATE))
        assertTrue(rig.await().settled)
        assertTrue("settled at ${rig.now}", rig.now >= 550)
        assertEquals(550, rig.now)
    }

    @Test fun aScreenThatNeverStopsIsReadAtTheCapUnsettled() {
        val rig = Rig((0L..5000L step 50).map { it to Kind.CONTENT })
        val settle = rig.await()
        assertFalse(settle.settled)
        assertEquals("events-still-arriving", settle.reason)
        assertEquals(1500, rig.now)
    }

    @Test fun eventsFromBeforeTheActionDoNotCount() {
        val rig = Rig()
        rig.now = 1000
        rig.log.record(Kind.STATE, "com.example.shop", 900)
        rig.log.record(Kind.CONTENT, "com.example.shop", 990)
        assertTrue(rig.await(since = 1000, actedAt = 1050).settled)
        assertEquals(1250, rig.now)
    }

    @Test fun aTargetIsWaitedFor() {
        val rig = Rig()
        assertTrue(rig.await(until = { rig.now >= 900 }).settled)
        assertEquals(900, rig.now)
    }

    @Test fun aTargetThatNeverArrivesIsNamed() {
        val rig = Rig()
        val settle = rig.await(SettlePolicy.LAUNCH, until = { false })
        assertFalse(settle.settled)
        assertEquals("target-not-in-front", settle.reason)
        assertEquals(3000, rig.now)
    }

    @Test fun theSleeperIsNeverAskedPastTheCap() {
        val rig = Rig((0L..5000L step 7).map { it to Kind.SCROLL })
        rig.now = 3
        assertFalse(rig.await(SettlePolicy.SCROLL, since = 0, actedAt = 40).settled)
        assertEquals(1200, rig.now)
        assertTrue(rig.sleeps.all { it in 1..Settler.STEP_MS })
    }

    @Test fun theStatusBarRedrawingIsNotTheApp() {
        val log = EventLog()
        log.record(Kind.CONTENT, EventLog.SYSTEM_UI, 100)
        log.record(Kind.SCROLL, EventLog.SYSTEM_UI, 110)
        assertEquals(EventLog.NEVER, log.lastAnyAt)
        log.record(Kind.STATE, EventLog.SYSTEM_UI, 120)
        assertEquals(120, log.lastAnyAt)
        assertEquals(120, log.lastStateAt)
        assertEquals(1, log.stateSeq)
        assertTrue(log.stateSeenFor(EventLog.SYSTEM_UI, 120))
        log.record(Kind.WINDOWS, "", 130)
        assertEquals(EventLog.SYSTEM_UI, log.lastStatePkg)
        assertEquals(2, log.stateSeq)
        assertFalse(log.stateSeenFor(EventLog.SYSTEM_UI, 121))
    }
}
