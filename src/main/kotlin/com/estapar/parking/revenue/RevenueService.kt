package com.estapar.parking.revenue

import com.estapar.parking.domain.RevenueLedgerEntry
import com.estapar.parking.domain.SectorMissingException
import com.estapar.parking.garage.PricingPolicy
import com.estapar.parking.ledger.RevenueLedgerRepository
import com.estapar.parking.sector.SectorService
import com.estapar.parking.session.SessionService
import org.springframework.stereotype.Service
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

@Service
class RevenueService(
    private val sessionService: SessionService,
    private val sectorService: SectorService,
    private val ledger: RevenueLedgerRepository,
    private val pricing: PricingPolicy,
    private val clock: Clock,
) {

    fun revenueFor(date: LocalDate, sector: String): RevenueResponse {
        sectorService.findByName(sector) ?: throw SectorNotFoundException(sector)

        val start = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        val end = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val amount = ledger.sumRevenue(sector, CURRENCY, start, end).setScale(2, RoundingMode.HALF_EVEN)

        return RevenueResponse(
            amount = amount,
            currency = CURRENCY,
            timestamp = clock.instant(),
        )
    }

    fun addRevenue(event: AddToRevenueEvent) {
        val session = sessionService.findById(event.sessionId)
            ?: error("Sessão ${event.sessionId} referenciada por AddToRevenueEvent não existe")
        val sectorName = requireNotNull(session.sector) { "Sessão ${event.sessionId} sem sector" }
        val multiplier = requireNotNull(session.priceMultiplier) { "Sessão ${event.sessionId} sem price_multiplier" }

        val sector = sectorService.findByName(sectorName) ?: throw SectorMissingException(sectorName)

        val seconds = Duration.between(session.entryTime, event.exitTime).seconds
        val amount = pricing.feeFor(seconds, sector.basePrice, multiplier)

        ledger.save(
            RevenueLedgerEntry(
                sessionId = event.sessionId,
                sector = sectorName,
                amount = amount,
                currency = CURRENCY,
                earnedAt = event.exitTime,
                createdAt = clock.instant(),
            ),
        )
    }

    private companion object {
        const val CURRENCY = "BRL"
    }
}
