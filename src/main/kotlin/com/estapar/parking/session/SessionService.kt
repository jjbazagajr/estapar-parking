package com.estapar.parking.session

import com.estapar.parking.domain.ParkingSession
import com.estapar.parking.domain.SessionAlreadyExitedException
import com.estapar.parking.domain.SessionAlreadyOpenException
import com.estapar.parking.domain.SessionAlreadyParkedException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

@Service
class SessionService(
    private val sessions: ParkingSessionRepository,
) {

    fun openByPlate(plate: String, entryTime: Instant): ParkingSession {
        if (sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate) != null) {
            throw SessionAlreadyOpenException(plate)
        }
        return sessions.save(ParkingSession(licensePlate = plate, entryTime = entryTime))
    }

    fun findOpenByPlate(plate: String): ParkingSession? =
        sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate)

    fun findById(id: Long): ParkingSession? =
        sessions.findById(id).orElse(null)

    fun markParked(session: ParkingSession, parkedAt: Instant, sector: String, spotId: Long, multiplier: BigDecimal) {
        session.park(parkedAt, sector, spotId, multiplier)
        try {
            sessions.saveAndFlush(session)
        } catch (_: ObjectOptimisticLockingFailureException) {
            throw SessionAlreadyParkedException(session.licensePlate)
        }
    }

    fun markExited(session: ParkingSession, exitTime: Instant) {
        session.exit(exitTime)
        try {
            sessions.saveAndFlush(session)
        } catch (_: ObjectOptimisticLockingFailureException) {
            throw SessionAlreadyExitedException(session.licensePlate)
        }
    }
}
