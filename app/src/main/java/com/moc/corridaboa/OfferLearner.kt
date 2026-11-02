package com.moc.corridaboa

/** Pure local rule: adjusts only an already borderline, positive-profit offer. */
internal object OfferLearner {
    const val MIN_SAMPLES = 5

    fun adjust(
        status: Int,
        goodStatus: Int,
        borderlineStatus: Int,
        profit: Double,
        pattern: ObservedOfferPattern?,
        minGrossKm: Double,
        minGrossHour: Double
    ): Int {
        if (status != borderlineStatus || profit <= 0.0 || pattern == null ||
            pattern.observations < MIN_SAMPLES) return status
        return when {
            pattern.averageNet <= 0.0 -> OverlayManager.BAD
            pattern.averageGrossKm >= minGrossKm && pattern.averageGrossHour >= minGrossHour -> goodStatus
            else -> status
        }
    }
}
