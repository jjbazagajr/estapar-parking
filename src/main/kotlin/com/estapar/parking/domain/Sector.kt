package com.estapar.parking.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.LocalTime
import java.util.UUID

@Entity
@Table(name = "sectors")
class Sector(
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36)
    var id: UUID = UUID.randomUUID(),

    @Column(name = "name", nullable = false, length = 32, unique = true)
    var name: String,

    @Column(name = "base_price", nullable = false)
    var basePrice: BigDecimal,

    @Column(name = "max_capacity", nullable = false)
    var maxCapacity: Int,

    @Column(name = "open_hour")
    var openHour: LocalTime? = null,

    @Column(name = "close_hour")
    var closeHour: LocalTime? = null,

    @Column(name = "duration_limit_minutes")
    var durationLimitMinutes: Int? = null,
)
