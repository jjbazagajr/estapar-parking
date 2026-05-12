package com.estapar.parking.ledger

import com.estapar.parking.domain.RevenueLedgerEntry
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Instant

interface RevenueLedgerRepository : JpaRepository<RevenueLedgerEntry, Long> {

    @Query(
        """
        SELECT COALESCE(SUM(e.amount), 0)
        FROM RevenueLedgerEntry e
        WHERE e.sector = :sector
          AND e.currency = :currency
          AND e.earnedAt >= :start
          AND e.earnedAt < :end
        """
    )
    fun sumRevenue(
        @Param("sector") sector: String,
        @Param("currency") currency: String,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): BigDecimal
}
