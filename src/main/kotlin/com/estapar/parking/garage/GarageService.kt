package com.estapar.parking.garage

import com.estapar.parking.domain.ParkingSession
import com.estapar.parking.domain.ParkingSessionRepository
import com.estapar.parking.domain.SectorRepository
import com.estapar.parking.domain.SpotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
class GarageService(
    private val sessions: ParkingSessionRepository,
    private val spots: SpotRepository,
    private val sectors: SectorRepository,
    private val clock: Clock,
    private val pricing: PricingPolicy,
) {

    @Transactional
    fun registerEntry(plate: String, time: LocalDateTime) {
        if (sectors.findAll().none { it.isOpenAt(time.toLocalTime()) }) {
            throw GarageClosedException()
        }
        if (sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate) != null) {
            throw SessionAlreadyOpenException(plate)
        }

        sessions.save(
            ParkingSession(
                licensePlate = plate,
                entryTime = time.toInstant(ZoneOffset.UTC),
            ),
        )
    }

    @Transactional
    fun parkVehicle(plate: String, lat: Double, lng: Double) {
        val session = sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate)
            ?: throw SessionNotFoundException(plate)

        val spot = spots.findFirstByLatAndLng(lat, lng)
            ?: throw SpotNotFoundException(lat, lng)
        if (spot.occupied) throw SpotAlreadyOccupiedException(lat, lng)

        val sector = sectors.findByNameForUpdate(spot.sector)
            ?: throw SectorMissingException(spot.sector)

        val now = clock.instant()
        if (!sector.isOpenAt(now.atZone(ZoneOffset.UTC).toLocalTime())) {
            throw SectorClosedException(sector.name)
        }

        val occupied = spots.countBySectorAndOccupiedTrue(sector.name)
        if (occupied >= sector.maxCapacity) throw SectorFullException(sector.name)

        val multiplier = pricing.multiplierFor(occupied.toDouble() / sector.maxCapacity)

        val taken = spots.tryOccupy(spot.id)
        if (taken == 0) throw SpotAlreadyOccupiedException(lat, lng)

        val claimed = sessions.markParked(
            id = requireNotNull(session.id) { "session sem id" },
            parkedTime = now,
            sector = spot.sector,
            spotId = spot.id,
            multiplier = multiplier,
        )
        if (claimed == 0) throw SessionAlreadyParkedException(plate)
    }

    @Transactional
    fun processExit(plate: String, time: LocalDateTime) {
        val session = sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate)
            ?: throw SessionNotFoundException(plate)
        val spotId = session.spotId ?: throw SessionNotParkedException(plate)
        val sectorName = session.sector ?: throw SessionNotParkedException(plate)
        val multiplier = session.priceMultiplier ?: throw SessionNotParkedException(plate)

        val exitInstant = time.toInstant(ZoneOffset.UTC)
        val seconds = Duration.between(session.entryTime, exitInstant).seconds
        if (seconds < 0) error("exit_time anterior a entry_time para placa $plate")

        val spot = spots.findById(spotId).orElse(null)
            ?: error("Spot $spotId referenciado pela sessão $plate não existe")
        val sector = sectors.findByName(sectorName)
            ?: throw SectorMissingException(sectorName)

        val amount = pricing.feeFor(seconds, sector.basePrice, multiplier)

        val closed = sessions.markExited(
            id = requireNotNull(session.id) { "session sem id" },
            exitTime = exitInstant,
            amount = amount,
        )
        if (closed == 0) throw SessionAlreadyExitedException(plate)

        spot.occupied = false
    }
}
