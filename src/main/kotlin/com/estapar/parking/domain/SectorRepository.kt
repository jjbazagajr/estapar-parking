package com.estapar.parking.domain

import org.springframework.data.jpa.repository.JpaRepository

interface SectorRepository : JpaRepository<Sector, String>
