package com.estapar.parking.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.LocalTime

@Entity
@Table(name = "sectors")
class Sector(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null,

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

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
) {

    fun isOpenAt(time: LocalTime): Boolean {
        val open = openHour ?: return true
        val close = closeHour ?: return true
        return time in open..close
    }

    fun syncWith(
        basePrice: BigDecimal,
        maxCapacity: Int,
        openHour: LocalTime?,
        closeHour: LocalTime?,
        durationLimitMinutes: Int?,
    ) {
        this.basePrice = basePrice
        this.maxCapacity = maxCapacity
        this.openHour = openHour
        this.closeHour = closeHour
        this.durationLimitMinutes = durationLimitMinutes
    }
}
