package com.moc.corridaboa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalContextAdvisorTest {
    @Test fun positiveBorderlineOfferStartingInUserReportedGoodMorningAreaCanBeTaken() {
        assertTrue(LocalContextAdvisor.shouldLeanTakeBorderline(
            profit = 8.63, borderline = true, pickup = "Rua Estados Unidos 855, Independência",
            currentArea = "", hour = 8, favorableDestination = false
        ))
    }

    @Test fun currentGpsAreaCanSupportMorningPriorWhenPickupTextIsNotRecognized() {
        assertTrue(LocalContextAdvisor.shouldLeanTakeBorderline(
            profit = 8.63, borderline = true, pickup = "", currentArea = "Independência",
            hour = 8, favorableDestination = false
        ))
    }

    @Test fun unidentifiedDestinationDoesNotGetInventedAsFavorable() {
        assertFalse(LocalContextAdvisor.shouldLeanTakeBorderline(
            profit = 8.63, borderline = true, pickup = "Centro", currentArea = "Centro",
            hour = 8, favorableDestination = false
        ))
    }

    @Test fun noMorningPriorOutsideWindow() {
        assertFalse(LocalContextAdvisor.favorableMorningPickup("Independência", "", 11))
    }

    @Test fun localPriorNeverRescuesNonpositiveProfitOrNonBorderlineOffer() {
        assertFalse(LocalContextAdvisor.shouldLeanTakeBorderline(
            profit = 0.0, borderline = true, pickup = "Independência", currentArea = "",
            hour = 8, favorableDestination = false
        ))
        assertFalse(LocalContextAdvisor.shouldLeanTakeBorderline(
            profit = 8.0, borderline = false, pickup = "Independência", currentArea = "",
            hour = 8, favorableDestination = false
        ))
    }
}
