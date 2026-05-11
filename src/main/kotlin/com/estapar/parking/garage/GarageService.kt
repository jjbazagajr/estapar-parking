package com.estapar.parking.garage

import com.estapar.parking.domain.ParkingSession
import com.estapar.parking.domain.ParkingSessionRepository
import com.estapar.parking.domain.SectorRepository
import com.estapar.parking.domain.SpotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
class GarageService(
    private val sessions: ParkingSessionRepository,
    private val spots: SpotRepository,
    private val sectors: SectorRepository,
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

    private fun priceMultiplierFor(occupancy: Double): BigDecimal = when {
        occupancy < 0.25 -> EMPTY
        occupancy < 0.50 -> NORMAL
        occupancy < 0.75 -> HIGH
        else -> PEAK
    }

    private companion object {
        val EMPTY = BigDecimal("0.90")
        val NORMAL = BigDecimal("1.00")
        val HIGH = BigDecimal("1.10")
        val PEAK = BigDecimal("1.25")
    }
}
