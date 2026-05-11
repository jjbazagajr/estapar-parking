package com.estapar.parking.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SpotRepository : JpaRepository<Spot, Long> {
    fun countByOccupiedTrue(): Long
    fun countBySectorAndOccupiedTrue(sector: String): Long
    fun countBySector(sector: String): Long
    fun findFirstByLatAndLngAndOccupiedFalse(lat: Double, lng: Double): Spot?
    fun findFirstByLatAndLng(lat: Double, lng: Double): Spot?

    @Modifying
    @Query(
        """
        UPDATE Spot s
           SET s.occupied = true
         WHERE s.id = :id
           AND s.occupied = false
        """
    )
    fun tryOccupy(@Param("id") id: Long): Int
}
