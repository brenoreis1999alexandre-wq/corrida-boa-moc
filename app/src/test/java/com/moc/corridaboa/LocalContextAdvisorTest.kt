package com.moc.corridaboa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalContextAdvisorTest {
    @Test fun takesNeighborhoodAfterCommaAndSkipsHouseNumberAndCity() {
        assertEquals("Jardim Brasil", LocalContextAdvisor.areaFromAddress("R. Plínio Ribeiro, 539, Jardim Brasil, Montes Claros"))
        assertEquals("Conjunto Residencial JK", LocalContextAdvisor.areaFromAddress("Av. Osmani Barbosa, Conjunto Residencial JK, Montes Claros"))
    }

    @Test fun centerRestrictionEndsAt1030Exactly() {
        assertTrue(LocalContextAdvisor.centerBlocked("Centro", 5, 0))
        assertTrue(LocalContextAdvisor.centerBlocked("Centro", 10, 29))
        assertFalse(LocalContextAdvisor.centerBlocked("Centro", 10, 30))
        assertFalse(LocalContextAdvisor.centerBlocked("Centro", 11, 0))
        assertFalse(LocalContextAdvisor.centerBlocked("Jardim Brasil", 8, 0))
    }
}
