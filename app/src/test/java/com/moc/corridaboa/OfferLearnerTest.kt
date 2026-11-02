package com.moc.corridaboa

import org.junit.Assert.assertEquals
import org.junit.Test

class OfferLearnerTest {
    private val good = OverlayManager.GOOD
    private val maybe = OverlayManager.MAYBE
    private val bad = OverlayManager.BAD
    private fun pattern(n: Int, net: Double, km: Double = 2.0, hour: Double = 35.0) =
        ObservedOfferPattern(n, net, km, hour)

    @Test fun needsFiveComparableObservations() {
        assertEquals(maybe, OfferLearner.adjust(maybe, good, maybe, 10.0, pattern(4, 0.0), 1.5, 31.0))
    }
    @Test fun positiveHistoricalMarginsCanLiftBorderlineByOneLevel() {
        assertEquals(good, OfferLearner.adjust(maybe, good, maybe, 10.0, pattern(5, 2.0), 1.5, 31.0))
    }
    @Test fun weakHistoricalMarginCanLowerBorderlineByOneLevel() {
        assertEquals(bad, OfferLearner.adjust(maybe, good, maybe, 10.0, pattern(8, -1.0), 1.5, 31.0))
    }
    @Test fun neverOverridesNonpositiveProfit() {
        assertEquals(maybe, OfferLearner.adjust(maybe, good, maybe, 0.0, pattern(9, 10.0), 1.5, 31.0))
    }
    @Test fun neverChangesClearlyGoodOrBadOffers() {
        assertEquals(good, OfferLearner.adjust(good, good, maybe, 10.0, pattern(20, -1.0), 1.5, 31.0))
        assertEquals(bad, OfferLearner.adjust(bad, good, maybe, 10.0, pattern(20, 10.0), 1.5, 31.0))
    }
    @Test fun positiveNetButWeakHistoricalRatesDoNotLiftOffer() {
        assertEquals(maybe, OfferLearner.adjust(maybe, good, maybe, 10.0, pattern(5, 1.0, 1.0, 20.0), 1.5, 31.0))
    }
}
