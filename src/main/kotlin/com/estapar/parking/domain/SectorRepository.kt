package com.estapar.parking.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SectorRepository : JpaRepository<Sector, Long> {
    fun findByName(name: String): Sector?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Sector s WHERE s.name = :name")
    fun findByNameForUpdate(@Param("name") name: String): Sector?
}
