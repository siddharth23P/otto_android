package dev.otto.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitiesTest {
    @Test fun protocolOneKnowsSessionsAndTurnsOnly() {
        val v1 = Capabilities.V1
        for (op in listOf(Op.SESSIONS_LIST, Op.SESSIONS_OPEN, Op.SESSIONS_TRANSCRIPT, Op.SESSIONS_DELETE, Op.TURN, Op.ANSWER, Op.CANCEL)) {
            assertTrue(op.key, v1.supports(op))
        }
        for (op in Op.entries - Capabilities.V1_OPS) assertFalse(op.key, v1.supports(op))
        assertFalse(v1.echoesIds)
    }

    @Test fun protocolOneIgnoresClaimedFeatures() {
        assertFalse(Capabilities(1, setOf("routing", "sessions.rename")).supports(Op.ROUTING_PIN))
    }

    @Test fun protocolTwoOffersWhatItsFeaturesName() {
        val caps = Capabilities.of(Hello(protocolVersion = 2, features = listOf("sessions.rename", "routing", "turn.phone")))
        assertTrue(caps.supports(Op.SESSIONS_RENAME))
        assertFalse(caps.supports(Op.SESSIONS_EXPORT))
        assertTrue(caps.supports(Op.ROUTING_LIST) && caps.supports(Op.ROUTING_PIN) && caps.supports(Op.ROUTING_CLEAR))
        assertTrue(caps.supports(Op.TURN_PHONE))
        assertFalse(caps.supports(Op.LESSONS_LIST))
        assertTrue(caps.supports(Op.SESSIONS_LIST))
        assertTrue(caps.echoesIds)
    }

    @Test fun aReplyFindsItsOp() {
        assertEquals(Op.SESSIONS_LIST, Op.of("sessions", "list"))
        assertEquals(Op.DOCTOR, Op.of("doctor", null))
        assertEquals(Op.DOCTOR, Op.of("doctor", ""))
        assertNull(Op.of("sessions", "nope"))
        assertEquals("setup_result", Op.SETUP_PROBE.resultType)
        assertEquals("setup.probe", Op.SETUP_PROBE.key)
    }
}
