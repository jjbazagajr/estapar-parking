package com.estapar.parking.revenue

import com.estapar.parking.domain.ParkingSessionRepository
import com.estapar.parking.domain.RevenueLedgerEntry
import com.estapar.parking.domain.RevenueLedgerRepository
import com.estapar.parking.domain.SectorRepository
import com.estapar.parking.garage.PricingPolicy
import com.estapar.parking.garage.SectorMissingException
import org.springframework.stereotype.Service
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

@Service
class RevenueService(
    private val sessions: ParkingSessionRepository,
    private val sectors: SectorRepository,
    private val ledger: RevenueLedgerRepository,
    private val pricing: PricingPolicy,
    private val clock: Clock,
) {

    fun revenueFor(date: LocalDate, sector: String): RevenueResponse {
        sectors.findByName(sector) ?: throw SectorNotFoundException(sector)

        val start = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val amount = ledger.sumRevenue(sector, start, end).setScale(2, RoundingMode.HALF_EVEN)

        return RevenueResponse(
            amount = amount,
            currency = "BRL",
            timestamp = clock.instant(),
        )
    }

    fun addRevenue(event: AddToRevenueEvent) {
        val session = sessions.findById(event.sessionId).orElse(null)
            ?: error("Sessão ${event.sessionId} referenciada por AddToRevenueEvent não existe")
        val sectorName = requireNotNull(session.sector) { "Sessão ${event.sessionId} sem sector" }
        val multiplier = requireNotNull(session.priceMultiplier) { "Sessão ${event.sessionId} sem price_multiplier" }

        val sector = sectors.findByName(sectorName) ?: throw SectorMissingException(sectorName)

        val seconds = Duration.between(session.entryTime, event.exitTime).seconds
        val amount = pricing.feeFor(seconds, sector.basePrice, multiplier)

        ledger.save(
            RevenueLedgerEntry(
                sessionId = event.sessionId,
                sector = sectorName,
                amount = amount,
                earnedAt = event.exitTime,
                createdAt = clock.instant(),
            ),
        )
    }
}
