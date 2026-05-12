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
@Table(name = "parking_sessions")
class ParkingSession(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null,

    @Column(name = "license_plate", nullable = false, length = 16)
    var licensePlate: String,

    @Column(name = "entry_time", nullable = false)
    var entryTime: Instant,

    @Column(name = "price_multiplier")
    var priceMultiplier: BigDecimal? = null,

    @Column(name = "sector")
    var sector: String? = null,

    @Column(name = "spot_id")
    var spotId: Long? = null,

    @Column(name = "parked_time")
    var parkedTime: Instant? = null,

    @Column(name = "exit_time")
    var exitTime: Instant? = null,
)
