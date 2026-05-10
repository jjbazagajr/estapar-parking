package com.estapar.parking.revenue

import com.estapar.parking.domain.ParkingSessionRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

@Service
class RevenueService(
    private val sessions: ParkingSessionRepository,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun revenueFor(date: LocalDate, sector: String): RevenueResponse {
        // TODO: somar amount_charged das sessões com exit_time no dia/setor informados
        return RevenueResponse(
            amount = BigDecimal.ZERO,
            currency = "BRL",
            timestamp = clock.instant(),
        )
    }
}
