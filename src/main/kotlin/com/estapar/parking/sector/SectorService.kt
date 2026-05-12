package com.estapar.parking.sector

import com.estapar.parking.domain.Sector
import org.springframework.stereotype.Service
import java.time.LocalTime

@Service
class SectorService(
    private val sectors: SectorRepository,
) {

    fun findByName(name: String): Sector? = sectors.findByName(name)

    fun lockByName(name: String): Sector? = sectors.findByNameForUpdate(name)

    fun isAnyOpenAt(time: LocalTime): Boolean =
        sectors.findAll().any { it.isOpenAt(time) }

    fun save(sector: Sector): Sector = sectors.save(sector)
}
