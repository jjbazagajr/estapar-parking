package com.estapar.parking.session

import com.estapar.parking.domain.ParkingSession
import org.springframework.data.jpa.repository.JpaRepository

interface ParkingSessionRepository : JpaRepository<ParkingSession, Long> {

    fun findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(licensePlate: String): ParkingSession?
}
