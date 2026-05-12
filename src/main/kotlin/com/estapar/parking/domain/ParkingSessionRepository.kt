package com.estapar.parking.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Instant

interface ParkingSessionRepository : JpaRepository<ParkingSession, Long> {

    fun findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(licensePlate: String): ParkingSession?

    @Modifying
    @Query(
        """
        UPDATE ParkingSession s
           SET s.parkedTime = :parkedTime,
               s.sector = :sector,
               s.spotId = :spotId,
               s.priceMultiplier = :multiplier
         WHERE s.id = :id
           AND s.parkedTime IS NULL
        """
    )
    fun markParked(
        @Param("id") id: Long,
        @Param("parkedTime") parkedTime: Instant,
        @Param("sector") sector: String,
        @Param("spotId") spotId: Long,
        @Param("multiplier") multiplier: BigDecimal,
    ): Int

    @Modifying
    @Query(
        """
        UPDATE ParkingSession s
           SET s.exitTime = :exitTime
         WHERE s.id = :id
           AND s.exitTime IS NULL
        """
    )
    fun markExited(
        @Param("id") id: Long,
        @Param("exitTime") exitTime: Instant,
    ): Int
}
