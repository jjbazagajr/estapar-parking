package com.estapar.parking.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

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
)
