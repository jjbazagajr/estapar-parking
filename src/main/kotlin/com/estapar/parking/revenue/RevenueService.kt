package com.estapar.parking.revenue

import com.estapar.parking.domain.ParkingSessionRepository
import com.estapar.parking.domain.SectorRepository
import org.springframework.stereotype.Service
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

@Service
class RevenueService(
    private val sessions: ParkingSessionRepository,
    private val sectors: SectorRepository,
    private val clock: Clock,
) {

    fun revenueFor(date: LocalDate, sector: String): RevenueResponse {
        sectors.findByName(sector) ?: throw SectorNotFoundException(sector)

        val start = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val amount = sessions.sumRevenue(sector, start, end).setScale(2, RoundingMode.HALF_EVEN)

        return RevenueResponse(
            amount = amount,
            currency = "BRL",
            timestamp = clock.instant(),
        )
    }
}
