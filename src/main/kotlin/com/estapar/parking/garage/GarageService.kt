package com.estapar.parking.garage

import com.estapar.parking.domain.ParkingSession
import com.estapar.parking.domain.ParkingSessionRepository
import com.estapar.parking.domain.SectorRepository
import com.estapar.parking.domain.SpotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
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
) {

    @Transactional
    fun registerEntry(plate: String, time: LocalDateTime) {
        if (sectors.findAll().none { it.isOpenAt(time.toLocalTime()) }) {
            throw GarageClosedException()
        }
        val total = spots.count()
        val occupied = spots.countByOccupiedTrue()
        if (total == 0L || occupied >= total) {
            throw GarageFullException()
        }
        if (sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate) != null) {
            throw SessionAlreadyOpenException(plate)
        }

        val multiplier = priceMultiplierFor(occupied.toDouble() / total)
        sessions.save(
            ParkingSession(
                licensePlate = plate,
                entryTime = time.toInstant(ZoneOffset.UTC),
                priceMultiplier = multiplier,
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

        val now = clock.instant()
        val sector = sectors.findByName(spot.sector)
            ?: throw SectorMissingException(spot.sector)
        if (!sector.isOpenAt(now.atZone(ZoneOffset.UTC).toLocalTime())) {
            throw SectorClosedException(sector.name)
        }

        val claimed = sessions.markParked(
            id = requireNotNull(session.id) { "session sem id" },
            parkedTime = now,
            sector = spot.sector,
            spotId = spot.id,
        )
        if (claimed == 0) throw SessionAlreadyParkedException(plate)

        spot.occupied = true
    }

    @Transactional
    fun processExit(plate: String, time: LocalDateTime) {
        val session = sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate)
            ?: throw SessionNotFoundException(plate)
        val spotId = session.spotId ?: throw SessionNotParkedException(plate)
        val sectorName = session.sector ?: throw SessionNotParkedException(plate)

        val exitInstant = time.toInstant(ZoneOffset.UTC)
        val seconds = Duration.between(session.entryTime, exitInstant).seconds
        if (seconds < 0) error("exit_time anterior a entry_time para placa $plate")

        val spot = spots.findById(spotId).orElse(null)
            ?: error("Spot $spotId referenciado pela sessão $plate não existe")
        val sector = sectors.findByName(sectorName)
            ?: throw SectorMissingException(sectorName)

        val amount = calculateFee(seconds, sector.basePrice, session.priceMultiplier)

        val closed = sessions.markExited(
            id = requireNotNull(session.id) { "session sem id" },
            exitTime = exitInstant,
            amount = amount,
        )
        if (closed == 0) throw SessionAlreadyExitedException(plate)

        spot.occupied = false
    }

    private fun priceMultiplierFor(occupancy: Double): BigDecimal = when {
        occupancy < 0.25 -> EMPTY
        occupancy < 0.50 -> NORMAL
        occupancy < 0.75 -> HIGH
        else -> PEAK
    }

    private fun calculateFee(seconds: Long, basePrice: BigDecimal, multiplier: BigDecimal): BigDecimal {
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
