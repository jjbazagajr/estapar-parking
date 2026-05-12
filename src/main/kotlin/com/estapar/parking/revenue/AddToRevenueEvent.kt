package com.estapar.parking.revenue

import java.time.Instant

data class AddToRevenueEvent(val sessionId: Long, val exitTime: Instant)
