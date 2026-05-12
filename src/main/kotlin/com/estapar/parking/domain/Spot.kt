package com.estapar.parking.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version

@Entity
@Table(name = "spots")
class Spot(
    @Id
    @Column(name = "id", nullable = false)
    var id: Long,

    @Column(name = "sector", nullable = false, length = 32)
    var sector: String,

    @Column(name = "lat", nullable = false)
    var lat: Double,

    @Column(name = "lng", nullable = false)
    var lng: Double,

    @Column(name = "occupied", nullable = false)
    var occupied: Boolean = false,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
) {

    fun occupy() {
        if (occupied) throw SpotAlreadyOccupiedException(lat, lng)
        occupied = true
    }

    fun release() {
        occupied = false
    }

    fun syncWith(sector: String, lat: Double, lng: Double, occupied: Boolean) {
        this.sector = sector
        this.lat = lat
        this.lng = lng
        this.occupied = occupied
    }
}
