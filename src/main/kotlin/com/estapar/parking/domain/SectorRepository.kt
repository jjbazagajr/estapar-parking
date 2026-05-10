package com.estapar.parking.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SectorRepository : JpaRepository<Sector, UUID>
