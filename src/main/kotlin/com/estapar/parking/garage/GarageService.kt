package com.estapar.parking.garage

import com.estapar.parking.domain.GarageClosedException
import com.estapar.parking.domain.SectorClosedException
import com.estapar.parking.domain.SectorFullException
import com.estapar.parking.domain.SectorMissingException
import com.estapar.parking.domain.SessionNotFoundException
import com.estapar.parking.domain.SessionNotParkedException
import com.estapar.parking.sector.SectorService
import com.estapar.parking.session.SessionService
import com.estapar.parking.spot.SpotService
import com.estapar.parking.revenue.AddToRevenueEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
class GarageService(
    private val sessionService: SessionService,
    private val spotService: SpotService,
    private val sectorService: SectorService,
    private val clock: Clock,
    private val pricing: PricingPolicy,
    private val events: ApplicationEventPublisher,
) {

    @Transactional
    fun registerEntry(plate: String, time: LocalDateTime) {
        if (!sectorService.isAnyOpenAt(time.toLocalTime())) {
            throw GarageClosedException()
        }
        sessionService.openByPlate(plate, time.toInstant(ZoneOffset.UTC))
    }

    @Transactional
    fun parkVehicle(plate: String, lat: Double, lng: Double) {
        val session = sessionService.findOpenByPlate(plate)
            ?: throw SessionNotFoundException(plate)

        val spot = spotService.findByCoordinates(lat, lng)
        val sector = sectorService.lockByName(spot.sector)
            ?: throw SectorMissingException(spot.sector)

        val now = clock.instant()
        if (!sector.isOpenAt(now.atZone(ZoneOffset.UTC).toLocalTime())) {
            throw SectorClosedException(sector.name)
        }

        val occupied = spotService.countOccupiedIn(sector.name)
        if (occupied >= sector.maxCapacity) throw SectorFullException(sector.name)

        val multiplier = pricing.multiplierFor(occupied.toDouble() / sector.maxCapacity)

        spotService.occupy(spot)
        sessionService.markParked(
            session = session,
            parkedAt = now,
            sector = spot.sector,
            spotId = spot.id,
            multiplier = multiplier,
        )
    }

    @Transactional
    fun processExit(plate: String, time: LocalDateTime) {
        val session = sessionService.findOpenByPlate(plate)
            ?: throw SessionNotFoundException(plate)
        val spotId = session.spotId ?: throw SessionNotParkedException(plate)

        val spot = spotService.findById(spotId)
            ?: error("Spot $spotId referenciado pela sessão $plate não existe")
        val exitInstant = time.toInstant(ZoneOffset.UTC)

        sessionService.markExited(session, exitInstant)
        spotService.release(spot)
        events.publishEvent(AddToRevenueEvent(requireNotNull(session.id), exitInstant))
    }
}
