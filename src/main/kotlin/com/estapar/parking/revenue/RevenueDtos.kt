package com.estapar.parking.revenue

import java.math.BigDecimal
import java.time.Instant

data class RevenueResponse(
    val amount: BigDecimal,
    val currency: String,
    val timestamp: Instant,
)
