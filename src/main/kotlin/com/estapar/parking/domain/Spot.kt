package com.estapar.parking.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.util.UUID

@Entity
@Table(name = "spots")
class Spot(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36)
    var id: UUID? = null,

    @Column(name = "sector", nullable = false, length = 32)
    var sector: String,

    @Column(name = "lat", nullable = false)
    var lat: Double,

    @Column(name = "lng", nullable = false)
    var lng: Double,

    @Column(name = "occupied", nullable = false)
    var occupied: Boolean = false,
)
