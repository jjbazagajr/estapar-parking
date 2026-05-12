package com.estapar.parking.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
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

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
) {

    fun park(parkedAt: Instant, sector: String, spotId: Long, multiplier: BigDecimal) {
        if (parkedTime != null) throw SessionAlreadyParkedException(licensePlate)
        this.parkedTime = parkedAt
        this.sector = sector
        this.spotId = spotId
        this.priceMultiplier = multiplier
    }

    fun exit(at: Instant) {
        if (exitTime != null) throw SessionAlreadyExitedException(licensePlate)
        if (parkedTime == null || sector == null || spotId == null || priceMultiplier == null) {
            throw SessionNotParkedException(licensePlate)
        }
        check(!at.isBefore(entryTime)) { "exit_time anterior a entry_time para placa $licensePlate" }
        this.exitTime = at
    }
}
