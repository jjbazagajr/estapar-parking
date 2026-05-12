package com.estapar.parking.domain

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ParkingSessionTest {

    private val plate = "ABC1D23"
    private val entryAt: Instant = Instant.parse("2026-05-10T12:00:00Z")

    @Test
    fun `given session aberta when park then preenche parkedTime, sector, spotId e priceMultiplier`() {
        // given
        val session = ParkingSession(licensePlate = plate, entryTime = entryAt)
        val parkedAt = entryAt.plusSeconds(60)

        // when
        session.park(parkedAt, sector = "A", spotId = 7L, multiplier = BigDecimal("0.900"))

        // then
        assertEquals(parkedAt, session.parkedTime)
        assertEquals("A", session.sector)
        assertEquals(7L, session.spotId)
        assertEquals(BigDecimal("0.900"), session.priceMultiplier)
    }

    @Test
    fun `given session ja parqueada when park then lanca SessionAlreadyParkedException`() {
        // given
        val session = ParkingSession(licensePlate = plate, entryTime = entryAt, parkedTime = entryAt)

        // when / then
        assertFailsWith<SessionAlreadyParkedException> {
            session.park(entryAt.plusSeconds(60), "A", 7L, BigDecimal("1.000"))
        }
    }

    @Test
    fun `given session parqueada when exit com tempo posterior then preenche exitTime`() {
        // given
        val session = ParkingSession(
            licensePlate = plate,
            entryTime = entryAt,
            parkedTime = entryAt,
            sector = "A",
            spotId = 7L,
            priceMultiplier = BigDecimal("1.000"),
        )
        val exitAt = entryAt.plusSeconds(3600)

        // when
        session.exit(exitAt)

        // then
        assertEquals(exitAt, session.exitTime)
    }

    @Test
    fun `given session ja encerrada when exit then lanca SessionAlreadyExitedException`() {
        // given
        val session = ParkingSession(
            licensePlate = plate,
            entryTime = entryAt,
            parkedTime = entryAt,
            sector = "A",
            spotId = 7L,
            priceMultiplier = BigDecimal("1.000"),
            exitTime = entryAt.plusSeconds(60),
        )

        // when / then
        assertFailsWith<SessionAlreadyExitedException> {
            session.exit(entryAt.plusSeconds(120))
        }
    }

    @Test
    fun `given session sem parkedTime when exit then lanca SessionNotParkedException`() {
        // given
        val session = ParkingSession(licensePlate = plate, entryTime = entryAt)

        // when / then
        assertFailsWith<SessionNotParkedException> {
            session.exit(entryAt.plusSeconds(60))
        }
    }

    @Test
    fun `given session parqueada when exit anterior ao entry then lanca IllegalStateException`() {
        // given
        val session = ParkingSession(
            licensePlate = plate,
            entryTime = entryAt,
            parkedTime = entryAt,
            sector = "A",
            spotId = 7L,
            priceMultiplier = BigDecimal("1.000"),
        )

        // when / then
        assertFailsWith<IllegalStateException> {
            session.exit(entryAt.minusSeconds(1))
        }
    }
}
