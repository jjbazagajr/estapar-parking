package com.estapar.parking.session

import com.estapar.parking.domain.ParkingSession
import com.estapar.parking.domain.SessionAlreadyExitedException
import com.estapar.parking.domain.SessionAlreadyOpenException
import com.estapar.parking.domain.SessionAlreadyParkedException
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SessionServiceTest {

    private val repo: ParkingSessionRepository = mock(ParkingSessionRepository::class.java)
    private val service = SessionService(repo)

    private val plate = "ABC1D23"
    private val entryAt: Instant = Instant.parse("2026-05-10T12:00:00Z")

    @Test
    fun `given placa sem sessao aberta when openByPlate then persiste nova sessao`() {
        // given
        `when`(repo.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate)).thenReturn(null)
        `when`(repo.save(any(ParkingSession::class.java) ?: emptySession()))
            .thenAnswer { it.arguments[0] as ParkingSession }

        // when
        val saved = service.openByPlate(plate, entryAt)

        // then
        val captor = ArgumentCaptor.forClass(ParkingSession::class.java)
        verify(repo).save(captor.capture())
        assertEquals(plate, captor.value.licensePlate)
        assertEquals(entryAt, captor.value.entryTime)
        assertNull(captor.value.parkedTime)
        assertEquals(plate, saved.licensePlate)
    }

    @Test
    fun `given placa com sessao aberta when openByPlate then lanca SessionAlreadyOpenException`() {
        // given
        `when`(repo.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate))
            .thenReturn(emptySession())

        // when / then
        assertFailsWith<SessionAlreadyOpenException> { service.openByPlate(plate, entryAt) }
    }

    @Test
    fun `given session valida when markParked then preenche campos e faz saveAndFlush`() {
        // given
        val session = emptySession()

        // when
        service.markParked(session, parkedAt = entryAt.plusSeconds(60), sector = "A", spotId = 7L, multiplier = BigDecimal("1.000"))

        // then
        assertEquals(entryAt.plusSeconds(60), session.parkedTime)
        assertEquals("A", session.sector)
        verify(repo).saveAndFlush(session)
    }

    @Test
    fun `given race no UPDATE when markParked then traduz ObjectOptimisticLockingFailureException em SessionAlreadyParkedException`() {
        // given
        val session = emptySession()
        `when`(repo.saveAndFlush(session))
            .thenThrow(ObjectOptimisticLockingFailureException(ParkingSession::class.java, 0L))

        // when / then
        assertFailsWith<SessionAlreadyParkedException> {
            service.markParked(session, entryAt.plusSeconds(60), "A", 7L, BigDecimal("1.000"))
        }
    }

    @Test
    fun `given session parqueada when markExited then preenche exitTime e faz saveAndFlush`() {
        // given
        val session = parkedSession()
        val exitAt = entryAt.plusSeconds(3600)

        // when
        service.markExited(session, exitAt)

        // then
        assertEquals(exitAt, session.exitTime)
        verify(repo).saveAndFlush(session)
    }

    @Test
    fun `given race no UPDATE when markExited then traduz ObjectOptimisticLockingFailureException em SessionAlreadyExitedException`() {
        // given
        val session = parkedSession()
        `when`(repo.saveAndFlush(session))
            .thenThrow(ObjectOptimisticLockingFailureException(ParkingSession::class.java, 0L))

        // when / then
        assertFailsWith<SessionAlreadyExitedException> {
            service.markExited(session, entryAt.plusSeconds(3600))
        }
    }

    @Test
    fun `given id existente when findById then retorna a sessao`() {
        // given
        val session = emptySession().apply { id = 42L }
        `when`(repo.findById(42L)).thenReturn(Optional.of(session))

        // when
        val found = service.findById(42L)

        // then
        assertEquals(42L, found?.id)
    }

    @Test
    fun `given id inexistente when findById then retorna null`() {
        // given
        `when`(repo.findById(99L)).thenReturn(Optional.empty())

        // when
        val found = service.findById(99L)

        // then
        assertNull(found)
    }

    private fun emptySession() = ParkingSession(licensePlate = plate, entryTime = entryAt)

    private fun parkedSession() = ParkingSession(
        id = 42L,
        licensePlate = plate,
        entryTime = entryAt,
        parkedTime = entryAt.plusSeconds(60),
        sector = "A",
        spotId = 7L,
        priceMultiplier = BigDecimal("1.000"),
    )
}
