package com.estapar.parking.spot

import com.estapar.parking.domain.Spot
import com.estapar.parking.domain.SpotAlreadyOccupiedException
import com.estapar.parking.domain.SpotNotFoundException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service

@Service
class SpotService(
    private val spots: SpotRepository,
) {

    fun findByCoordinates(lat: Double, lng: Double): Spot =
        spots.findFirstByLatAndLng(lat, lng) ?: throw SpotNotFoundException(lat, lng)

    fun findById(id: Long): Spot? =
        spots.findById(id).orElse(null)

    fun countOccupiedIn(sector: String): Long =
        spots.countBySectorAndOccupiedTrue(sector)

    fun occupy(spot: Spot) {
        spot.occupy()
        try {
            spots.saveAndFlush(spot)
        } catch (_: ObjectOptimisticLockingFailureException) {
            throw SpotAlreadyOccupiedException(spot.lat, spot.lng)
        }
    }

    fun release(spot: Spot) {
        spot.release()
    }

    fun save(spot: Spot): Spot = spots.save(spot)
}
