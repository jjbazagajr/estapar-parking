package com.estapar.parking.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

interface ParkingSessionRepository : JpaRepository<ParkingSession, UUID> {

    fun findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(licensePlate: String): ParkingSession?

    @Query(
        """
        SELECT COALESCE(SUM(s.amountCharged), 0)
        FROM ParkingSession s
        WHERE s.sector = :sector
          AND s.exitTime IS NOT NULL
          AND s.exitTime >= :start
          AND s.exitTime < :end
        """
    )
    fun sumRevenue(
        @Param("sector") sector: String,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): BigDecimal
}
