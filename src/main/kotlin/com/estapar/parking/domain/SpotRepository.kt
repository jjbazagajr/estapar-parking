package com.estapar.parking.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpotRepository : JpaRepository<Spot, UUID> {
    fun countBySectorAndOccupiedTrue(sector: String): Long
    fun countBySector(sector: String): Long
    fun findFirstByLatAndLngAndOccupiedFalse(lat: Double, lng: Double): Spot?
    fun findFirstByLatAndLng(lat: Double, lng: Double): Spot?
}
