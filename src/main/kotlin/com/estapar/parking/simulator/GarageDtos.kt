package com.estapar.parking.simulator

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.math.BigDecimal

@JsonIgnoreProperties(ignoreUnknown = true)
data class GarageResponse(
    val garage: List<SectorDto> = emptyList(),
    val spots: List<SpotDto> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SectorDto(
    val sector: String,
    @JsonAlias("base_price")
    val basePrice: BigDecimal,
    @JsonAlias("max_capacity")
    val maxCapacity: Int,
    @JsonAlias("open_hour")
    val openHour: String? = null,
    @JsonAlias("close_hour")
    val closeHour: String? = null,
    @JsonAlias("duration_limit_minutes")
    val durationLimitMinutes: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SpotDto(
    val id: Long,
    val sector: String,
    val lat: Double,
    val lng: Double,
    val occupied: Boolean = false,
)
