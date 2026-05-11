package com.estapar.parking.garage

import com.estapar.parking.domain.ParkingSession
import com.estapar.parking.domain.ParkingSessionRepository
import com.estapar.parking.domain.Sector
import com.estapar.parking.domain.SectorRepository
import com.estapar.parking.domain.SpotRepository
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GarageServiceTest {

    private val sessions: ParkingSessionRepository = mock(ParkingSessionRepository::class.java)
    private val spots: SpotRepository = mock(SpotRepository::class.java)
    private val sectors: SectorRepository = mock(SectorRepository::class.java)
    private val service = GarageService(sessions, spots, sectors)

    private val anyTime: LocalDateTime = LocalDateTime.of(2026, 5, 10, 12, 0)
    private val plate = "ABC1D23"

    @Test
    fun `given garagem com 20 porcento ocupacao when register entry then salva sessao com multiplicador 0_90`() {
        // given
        stubOpenGarage()
        stubOccupancy(total = 10, occupied = 2)
        stubNoOpenSession()

        // when
        service.registerEntry(plate, anyTime)

        // then
        assertSavedMultiplier(BigDecimal("0.90"))
    }

    @Test
    fun `given garagem com 49 porcento ocupacao when register entry then salva sessao com multiplicador 1_00`() {
        // given
        stubOpenGarage()
        stubOccupancy(total = 100, occupied = 49)
        stubNoOpenSession()

        // when
        service.registerEntry(plate, anyTime)

        // then
        assertSavedMultiplier(BigDecimal("1.00"))
    }

    @Test
    fun `given garagem com 74 porcento ocupacao when register entry then salva sessao com multiplicador 1_10`() {
        // given
        stubOpenGarage()
        stubOccupancy(total = 100, occupied = 74)
        stubNoOpenSession()

        // when
        service.registerEntry(plate, anyTime)

        // then
        assertSavedMultiplier(BigDecimal("1.10"))
    }

    @Test
    fun `given garagem com 99 porcento ocupacao when register entry then salva sessao com multiplicador 1_25`() {
        // given
        stubOpenGarage()
        stubOccupancy(total = 100, occupied = 99)
        stubNoOpenSession()

        // when
        service.registerEntry(plate, anyTime)

        // then
        assertSavedMultiplier(BigDecimal("1.25"))
    }

    @Test
    fun `given garagem vazia when register entry then salva sessao com multiplicador 0_90`() {
        // given
        stubOpenGarage()
        stubOccupancy(total = 10, occupied = 0)
        stubNoOpenSession()

        // when
        service.registerEntry(plate, anyTime)

        // then
        assertSavedMultiplier(BigDecimal("0.90"))
    }

    @Test
    fun `given garagem cheia when register entry then lanca GarageFullException`() {
        // given
        stubOpenGarage()
        stubOccupancy(total = 10, occupied = 10)
        stubNoOpenSession()

        // when / then
        assertFailsWith<GarageFullException> { service.registerEntry(plate, anyTime) }
    }

    @Test
    fun `given garagem sem spots cadastrados when register entry then lanca GarageFullException`() {
        // given
        stubOpenGarage()
        stubOccupancy(total = 0, occupied = 0)
        stubNoOpenSession()

        // when / then
        assertFailsWith<GarageFullException> { service.registerEntry(plate, anyTime) }
    }

    @Test
    fun `given placa com sessao aberta when register entry then lanca SessionAlreadyOpenException`() {
        // given
        stubOpenGarage()
        stubOccupancy(total = 10, occupied = 2)
        `when`(sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate))
            .thenReturn(ParkingSession(licensePlate = plate, entryTime = anyTime.toInstant(ZoneOffset.UTC), priceMultiplier = BigDecimal("1.00")))

        // when / then
        assertFailsWith<SessionAlreadyOpenException> { service.registerEntry(plate, anyTime) }
    }

    @Test
    fun `given todos setores fechados no horario when register entry then lanca GarageClosedException`() {
        // given
        val entryAt3am = LocalDateTime.of(2026, 5, 10, 3, 0)
        `when`(sectors.findAll()).thenReturn(
            listOf(sectorOpenBetween("A", LocalTime.of(8, 0), LocalTime.of(18, 0))),
        )

        // when / then
        assertFailsWith<GarageClosedException> { service.registerEntry(plate, entryAt3am) }
    }

    @Test
    fun `given pelo menos um setor aberto no horario when register entry then aceita`() {
        // given
        val entryAt3am = LocalDateTime.of(2026, 5, 10, 3, 0)
        `when`(sectors.findAll()).thenReturn(
            listOf(
                sectorOpenBetween("A", LocalTime.of(0, 0), LocalTime.of(23, 59)),
                sectorOpenBetween("B", LocalTime.of(8, 0), LocalTime.of(18, 0)),
            ),
        )
        stubOccupancy(total = 10, occupied = 2)
        stubNoOpenSession()

        // when
        service.registerEntry(plate, entryAt3am)

        // then
        verify(sessions).save(org.mockito.ArgumentMatchers.any(ParkingSession::class.java))
    }

    @Test
    fun `given setor sem open hour e close hour when register entry then trata como sempre aberto`() {
        // given
        `when`(sectors.findAll()).thenReturn(
            listOf(sectorOpenBetween("A", open = null, close = null)),
        )
        stubOccupancy(total = 10, occupied = 2)
        stubNoOpenSession()

        // when
        service.registerEntry(plate, anyTime)

        // then
        assertSavedMultiplier(BigDecimal("0.90"))
    }

    private fun stubOpenGarage() {
        `when`(sectors.findAll()).thenReturn(
            listOf(sectorOpenBetween("A", LocalTime.of(0, 0), LocalTime.of(23, 59))),
        )
    }

    private fun stubOccupancy(total: Long, occupied: Long) {
        `when`(spots.count()).thenReturn(total)
        `when`(spots.countByOccupiedTrue()).thenReturn(occupied)
    }

    private fun stubNoOpenSession() {
        `when`(sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate))
            .thenReturn(null)
    }

    private fun assertSavedMultiplier(expected: BigDecimal) {
        val captor = ArgumentCaptor.forClass(ParkingSession::class.java)
        verify(sessions).save(captor.capture())
        assertEquals(expected, captor.value.priceMultiplier)
        assertEquals(plate, captor.value.licensePlate)
    }

    private fun sectorOpenBetween(name: String, open: LocalTime?, close: LocalTime?) = Sector(
        name = name,
        basePrice = BigDecimal("10.00"),
        maxCapacity = 10,
        openHour = open,
        closeHour = close,
    )
}
