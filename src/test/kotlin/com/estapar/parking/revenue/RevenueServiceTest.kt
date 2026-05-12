package com.estapar.parking.revenue

import com.estapar.parking.domain.ParkingSessionRepository
import com.estapar.parking.domain.Sector
import com.estapar.parking.domain.SectorRepository
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RevenueServiceTest {

    private val sessions: ParkingSessionRepository = mock(ParkingSessionRepository::class.java)
    private val sectors: SectorRepository = mock(SectorRepository::class.java)
    private val fixedInstant: Instant = Instant.parse("2026-05-11T15:30:00Z")
    private val clock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val service = RevenueService(sessions, sectors, clock)

    private val anyDate: LocalDate = LocalDate.of(2025, 1, 1)
    private val sectorA = "A"

    @Test
    fun `given setor inexistente when revenueFor then lanca SectorNotFoundException e nao consulta sessions`() {
        // given
        `when`(sectors.findByName(sectorA)).thenReturn(null)

        // when / then
        assertFailsWith<SectorNotFoundException> { service.revenueFor(anyDate, sectorA) }
        verify(sessions, never()).sumRevenue(anyString(), anyInstantArg(), anyInstantArg())
    }

    @Test
    fun `given data 2025-01-01 when revenueFor then consulta sumRevenue com start 2025-01-01T00 00 UTC e end 2025-01-02T00 00 UTC`() {
        // given
        stubSectorExists(sectorA)
        stubSumRevenueAnyWindow(sectorA, BigDecimal.ZERO)

        // when
        service.revenueFor(LocalDate.of(2025, 1, 1), sectorA)

        // then
        val sectorCap = ArgumentCaptor.forClass(String::class.java)
        val startCap = ArgumentCaptor.forClass(Instant::class.java)
        val endCap = ArgumentCaptor.forClass(Instant::class.java)
        verify(sessions).sumRevenue(
            sectorCap.capture() ?: "",
            startCap.capture() ?: Instant.EPOCH,
            endCap.capture() ?: Instant.EPOCH,
        )
        assertEquals(sectorA, sectorCap.value)
        assertEquals(Instant.parse("2025-01-01T00:00:00Z"), startCap.value)
        assertEquals(Instant.parse("2025-01-02T00:00:00Z"), endCap.value)
    }

    @Test
    fun `given setor sem sessoes encerradas no dia when revenueFor then retorna amount 0_00 e currency BRL`() {
        // given
        stubSectorExists(sectorA)
        stubSumRevenueAnyWindow(sectorA, BigDecimal.ZERO)

        // when
        val response = service.revenueFor(anyDate, sectorA)

        // then
        assertEquals(BigDecimal("0.00"), response.amount)
        assertEquals("BRL", response.currency)
    }

    @Test
    fun `given setor com sessoes encerradas no dia somando 123_45 when revenueFor then retorna amount 123_45`() {
        // given
        stubSectorExists(sectorA)
        stubSumRevenueAnyWindow(sectorA, BigDecimal("123.45"))

        // when
        val response = service.revenueFor(anyDate, sectorA)

        // then
        assertEquals(BigDecimal("123.45"), response.amount)
    }

    @Test
    fun `given soma retornada com escala 4 (ex 123_4500) when revenueFor then normaliza para escala 2`() {
        // given
        stubSectorExists(sectorA)
        stubSumRevenueAnyWindow(sectorA, BigDecimal("123.4500"))

        // when
        val response = service.revenueFor(anyDate, sectorA)

        // then
        assertEquals(BigDecimal("123.45"), response.amount)
        assertEquals(2, response.amount.scale())
    }

    @Test
    fun `given clock fixo when revenueFor then timestamp e o instante do clock`() {
        // given
        stubSectorExists(sectorA)
        stubSumRevenueAnyWindow(sectorA, BigDecimal.ZERO)

        // when
        val response = service.revenueFor(anyDate, sectorA)

        // then
        assertEquals(fixedInstant, response.timestamp)
    }

    @Test
    fun `given setor valido when revenueFor then currency e BRL`() {
        // given
        stubSectorExists(sectorA)
        stubSumRevenueAnyWindow(sectorA, BigDecimal("9.99"))

        // when
        val response = service.revenueFor(anyDate, sectorA)

        // then
        assertEquals("BRL", response.currency)
    }

    private fun stubSectorExists(name: String) {
        `when`(sectors.findByName(name)).thenReturn(
            Sector(
                name = name,
                basePrice = BigDecimal("10.00"),
                maxCapacity = 10,
                openHour = null,
                closeHour = null,
            ),
        )
    }

    private fun stubSumRevenueAnyWindow(sector: String, sum: BigDecimal) {
        `when`(sessions.sumRevenue(anyString(), anyInstantArg(), anyInstantArg()))
            .thenReturn(sum)
    }

    private fun anyInstantArg(): Instant {
        org.mockito.ArgumentMatchers.any(Instant::class.java)
        return Instant.EPOCH
    }
}
