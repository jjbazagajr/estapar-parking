package com.estapar.parking.spot

import com.estapar.parking.domain.Spot
import org.springframework.data.jpa.repository.JpaRepository

interface SpotRepository : JpaRepository<Spot, Long> {
    fun countBySectorAndOccupiedTrue(sector: String): Long
    fun findFirstByLatAndLng(lat: Double, lng: Double): Spot?
}
