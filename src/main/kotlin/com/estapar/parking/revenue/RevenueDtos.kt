package com.estapar.parking.revenue

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class RevenueRequest(
    val date: LocalDate,
    val sector: String,
)

data class RevenueResponse(
    val amount: BigDecimal,
    val currency: String,
    val timestamp: Instant,
)
