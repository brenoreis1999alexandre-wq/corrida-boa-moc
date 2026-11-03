package com.moc.corridaboa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalContextAdvisorTest {
    @Test fun parsesNeighborhoodImmediatelyAfterStreetAndSkipsHouseNumber() {
        assertEquals("Jardim Brasil", LocalContextAdvisor.areaFromAddress("R. Plínio Ribeiro, 539, Jardim Brasil, Montes Claros, MG"))
        assertEquals("Conjunto Residencial JK", LocalContextAdvisor.areaFromAddress("Av. Osmani Barbosa, Conjunto Residencial JK, Montes Claros"))
        assertEquals("Centro", LocalContextAdvisor.areaFromAddress("Rua X, Centro, Montes Claros - MG, Brasil"))
    }

    @Test fun blocksCenterBefore1030AndOpensAt1030() {
        assertTrue(LocalContextAdvisor.centerBlocked("Centro", 5, 0))
        assertTrue(LocalContextAdvisor.centerBlocked("Centro", 10, 29))
        assertFalse(LocalContextAdvisor.centerBlocked("Centro", 10, 30))
        assertFalse(LocalContextAdvisor.centerBlocked("Jardim Brasil", 8, 0))
    }

    @Test fun usesSuppliedLocalProfilesForKnownDestinations() {
        assertEquals(LocalContextAdvisor.Level.HIGH, LocalContextAdvisor.level("Independência", 7, 0, java.util.Calendar.TUESDAY, false))
        assertEquals(LocalContextAdvisor.Level.LOW, LocalContextAdvisor.level("Centro", 6, 0, java.util.Calendar.TUESDAY, false))
        assertEquals(LocalContextAdvisor.Level.HIGH, LocalContextAdvisor.level("Centro", 11, 0, java.util.Calendar.TUESDAY, false))
        assertEquals(LocalContextAdvisor.Level.UNKNOWN, LocalContextAdvisor.level("Jardim Brasil", 7, 0, java.util.Calendar.TUESDAY, false))
    }
}
