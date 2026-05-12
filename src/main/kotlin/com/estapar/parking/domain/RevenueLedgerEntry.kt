package com.estapar.parking.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "revenue_ledger")
class RevenueLedgerEntry(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null,

    @Column(name = "session_id", nullable = false, unique = true)
    var sessionId: Long,

    @Column(name = "sector", nullable = false, length = 32)
    var sector: String,

    @Column(name = "amount", nullable = false)
    var amount: BigDecimal,

    @Column(name = "earned_at", nullable = false)
    var earnedAt: Instant,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,
)
