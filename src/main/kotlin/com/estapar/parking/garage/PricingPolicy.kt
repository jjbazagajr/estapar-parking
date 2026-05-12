package com.estapar.parking.garage

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class PricingPolicy {

    fun multiplierFor(occupancyRatio: Double): BigDecimal = when {
        occupancyRatio < 0.25 -> EMPTY
        occupancyRatio < 0.50 -> NORMAL
        occupancyRatio < 0.75 -> HIGH
        else -> PEAK
    }

    fun feeFor(seconds: Long, basePrice: BigDecimal, multiplier: BigDecimal): BigDecimal {
        if (seconds <= GRACE_PERIOD_SECONDS) return BigDecimal.ZERO.setScale(2)
        val hours = Math.ceilDiv(seconds, SECONDS_PER_HOUR).toBigDecimal()
        return basePrice.multiply(hours).multiply(multiplier).setScale(2, RoundingMode.HALF_EVEN)
    }

    private companion object {
        val EMPTY = BigDecimal("0.90")
        val NORMAL = BigDecimal("1.00")
        val HIGH = BigDecimal("1.10")
        val PEAK = BigDecimal("1.25")
        const val GRACE_PERIOD_SECONDS = 30L * 60
        const val SECONDS_PER_HOUR = 3600L
    }
}
