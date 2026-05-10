package com.estapar.parking.simulator

import com.estapar.parking.config.SimulatorProperties
import com.estapar.parking.domain.Sector
import com.estapar.parking.domain.SectorRepository
import com.estapar.parking.domain.Spot
import com.estapar.parking.domain.SpotRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Component
class GarageBootstrap(
    private val simulatorRestClient: RestClient,
    private val sectorRepository: SectorRepository,
    private val spotRepository: SpotRepository,
    private val properties: SimulatorProperties,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(GarageBootstrap::class.java)

    @Transactional
    override fun run(args: ApplicationArguments) {
        if (!properties.bootstrapEnabled) {
            log.info("Garage bootstrap disabled; skipping fetch from simulator.")
            return
        }
        try {
            val response = simulatorRestClient.get()
                .uri("/garage")
                .retrieve()
                .body(GarageResponse::class.java)
                ?: error("Empty /garage response from simulator")

            persist(response)
            log.info(
                "Garage loaded: {} sectors, {} spots",
                response.garage.size, response.spots.size,
            )
        } catch (ex: Exception) {
            log.warn(
                "Failed to fetch /garage from simulator at {}: {}. Application will continue; webhook events that depend on existing sectors/spots may fail until data is loaded.",
                properties.baseUrl, ex.message,
            )
        }
    }

    internal fun persist(response: GarageResponse) {
        response.garage.forEach { dto -> upsertSector(dto) }
        response.spots.forEach { dto -> upsertSpot(dto) }
    }

    private fun upsertSector(dto: SectorDto) {
        val sector = sectorRepository.findByName(dto.sector) ?: Sector(
            name = dto.sector,
            basePrice = dto.basePrice,
            maxCapacity = dto.maxCapacity,
        )
        sector.basePrice = dto.basePrice
        sector.maxCapacity = dto.maxCapacity
        sector.openHour = parseTime(dto.openHour)
        sector.closeHour = parseTime(dto.closeHour)
        sector.durationLimitMinutes = dto.durationLimitMinutes
        sectorRepository.save(sector)
    }

    private fun upsertSpot(dto: SpotDto) {
        val spot = spotRepository.findById(dto.id).orElse(null) ?: Spot(
            id = dto.id,
            sector = dto.sector,
            lat = dto.lat,
            lng = dto.lng,
        )
        spot.sector = dto.sector
        spot.lat = dto.lat
        spot.lng = dto.lng
        spot.occupied = dto.occupied
        spotRepository.save(spot)
    }

    private fun parseTime(value: String?): LocalTime? {
        if (value.isNullOrBlank()) return null
        return try {
            LocalTime.parse(value, TIME_FORMAT)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
