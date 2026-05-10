package com.estapar.parking.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "parking_sessions")
class ParkingSession(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36)
    var id: UUID? = null,

    @Column(name = "license_plate", nullable = false, length = 16)
    var licensePlate: String,

    @Column(name = "entry_time", nullable = false)
    var entryTime: Instant,

    @Column(name = "price_multiplier", nullable = false)
    var priceMultiplier: BigDecimal,

    @Column(name = "sector")
    var sector: String? = null,

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "spot_id", length = 36)
    var spotId: UUID? = null,

    @Column(name = "parked_time")
    var parkedTime: Instant? = null,

    @Column(name = "exit_time")
    var exitTime: Instant? = null,

    @Column(name = "amount_charged")
    var amountCharged: BigDecimal? = null,
)
