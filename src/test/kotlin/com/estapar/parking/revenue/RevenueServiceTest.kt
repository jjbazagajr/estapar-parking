package com.estapar.parking.revenue

import com.estapar.parking.domain.ParkingSession
import com.estapar.parking.domain.ParkingSessionRepository
import com.estapar.parking.domain.RevenueLedgerEntry
import com.estapar.parking.domain.RevenueLedgerRepository
import com.estapar.parking.domain.Sector
import com.estapar.parking.domain.SectorRepository
import com.estapar.parking.garage.PricingPolicy
import com.estapar.parking.garage.SectorMissingException
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
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RevenueServiceTest {

    private val sessions: ParkingSessionRepository = mock(ParkingSessionRepository::class.java)
    private val sectors: SectorRepository = mock(SectorRepository::class.java)
    private val ledger: RevenueLedgerRepository = mock(RevenueLedgerRepository::class.java)
    private val fixedInstant: Instant = Instant.parse("2026-05-11T15:30:00Z")
    private val clock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val service = RevenueService(sessions, sectors, ledger, PricingPolicy(), clock)

    private val anyDate: LocalDate = LocalDate.of(2025, 1, 1)
    private val sectorA = "A"

    private val sessionId = 42L
    private val entryAt: Instant = Instant.parse("2026-05-10T12:00:00Z")
    private val exitAt: Instant = entryAt.plusSeconds(3600)
    private val anyEvent = AddToRevenueEvent(sessionId, exitAt)

    @Test
    fun `given setor inexistente when revenueFor then lanca SectorNotFoundException e nao consulta ledger`() {
        // given
        `when`(sectors.findByName(sectorA)).thenReturn(null)

        // when / then
        assertFailsWith<SectorNotFoundException> { service.revenueFor(anyDate, sectorA) }
        verify(ledger, never()).sumRevenue(anyString(), anyString(), anyInstantArg(), anyInstantArg())
    }

    @Test
    fun `given data 2025-01-01 when revenueFor then consulta sumRevenue com sector currency BRL e janela 2025-01-01T00 00 UTC ate 2025-01-02T00 00 UTC`() {
        // given
        stubSectorExists(sectorA)
        stubSumRevenueAnyWindow(sectorA, BigDecimal.ZERO)

        // when
        service.revenueFor(LocalDate.of(2025, 1, 1), sectorA)

        // then
        val sectorCap = ArgumentCaptor.forClass(String::class.java)
        val currencyCap = ArgumentCaptor.forClass(String::class.java)
        val startCap = ArgumentCaptor.forClass(Instant::class.java)
        val endCap = ArgumentCaptor.forClass(Instant::class.java)
        verify(ledger).sumRevenue(
            sectorCap.capture() ?: "",
            currencyCap.capture() ?: "",
            startCap.capture() ?: Instant.EPOCH,
            endCap.capture() ?: Instant.EPOCH,
        )
        assertEquals(sectorA, sectorCap.value)
        assertEquals("BRL", currencyCap.value)
        assertEquals(Instant.parse("2025-01-01T00:00:00Z"), startCap.value)
        assertEquals(Instant.parse("2025-01-02T00:00:00Z"), endCap.value)
    }

    @Test
    fun `given setor sem lancamentos no dia when revenueFor then retorna amount 0_00 e currency BRL`() {
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
    fun `given setor com lancamentos no dia somando 123_45 when revenueFor then retorna amount 123_45`() {
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

    @Test
    fun `given session inexistente when addRevenue then lanca IllegalStateException e nao persiste ledger`() {
        // given
        `when`(sessions.findById(sessionId)).thenReturn(Optional.empty())

        // when / then
        assertFailsWith<IllegalStateException> { service.addRevenue(anyEvent) }
        verify(ledger, never()).save(org.mockito.ArgumentMatchers.any(RevenueLedgerEntry::class.java))
    }

    @Test
    fun `given session sem sector when addRevenue then lanca IllegalArgumentException`() {
        // given
        stubSession(session(sector = null))

        // when / then
        assertFailsWith<IllegalArgumentException> { service.addRevenue(anyEvent) }
    }

    @Test
    fun `given session sem price multiplier when addRevenue then lanca IllegalArgumentException`() {
        // given
        stubSession(session(multiplier = null))

        // when / then
        assertFailsWith<IllegalArgumentException> { service.addRevenue(anyEvent) }
    }

    @Test
    fun `given sector da session removido do banco when addRevenue then lanca SectorMissingException`() {
        // given
        stubSession(session())
        `when`(sectors.findByName("A")).thenReturn(null)

        // when / then
        assertFailsWith<SectorMissingException> { service.addRevenue(anyEvent) }
    }

    @Test
    fun `given session 1h multiplier 1_000 basePrice 40_50 when addRevenue then persiste ledger com amount 40_50, sector A, currency BRL, earnedAt do evento e createdAt do clock`() {
        // given
        stubSession(session(multiplier = BigDecimal("1.000")))
        stubSectorWithPrice("A", BigDecimal("40.50"))

        // when
        service.addRevenue(anyEvent)

        // then
        val captured = capturedEntry()
        assertEquals(sessionId, captured.sessionId)
        assertEquals("A", captured.sector)
        assertEquals(0, BigDecimal("40.50").compareTo(captured.amount))
        assertEquals("BRL", captured.currency)
        assertEquals(exitAt, captured.earnedAt)
        assertEquals(fixedInstant, captured.createdAt)
    }

    @Test
    fun `given session 1h multiplier 0_900 basePrice 40_50 when addRevenue then amount 36_45`() {
        // given
        stubSession(session(multiplier = BigDecimal("0.900")))
        stubSectorWithPrice("A", BigDecimal("40.50"))

        // when
        service.addRevenue(anyEvent)

        // then
        assertEquals(0, BigDecimal("36.45").compareTo(capturedEntry().amount))
    }

    @Test
    fun `given event com exitTime igual ao entryTime when addRevenue then amount 0_00`() {
        // given
        stubSession(session())
        stubSectorWithPrice("A", BigDecimal("40.50"))

        // when
        service.addRevenue(AddToRevenueEvent(sessionId, entryAt))

        // then
        assertEquals(0, BigDecimal("0.00").compareTo(capturedEntry().amount))
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
        `when`(ledger.sumRevenue(anyString(), anyString(), anyInstantArg(), anyInstantArg()))
            .thenReturn(sum)
    }

    private fun session(
        sector: String? = "A",
        multiplier: BigDecimal? = BigDecimal("1.000"),
    ) = ParkingSession(
        id = sessionId,
        licensePlate = "ABC1D23",
        entryTime = entryAt,
        priceMultiplier = multiplier,
        sector = sector,
        spotId = if (sector == null) null else 7L,
        parkedTime = entryAt,
    )

    private fun stubSession(session: ParkingSession) {
        `when`(sessions.findById(sessionId)).thenReturn(Optional.of(session))
    }

    private fun stubSectorWithPrice(name: String, basePrice: BigDecimal) {
        `when`(sectors.findByName(name)).thenReturn(
            Sector(
                name = name,
                basePrice = basePrice,
                maxCapacity = 10,
                openHour = null,
                closeHour = null,
            ),
        )
    }

    private fun capturedEntry(): RevenueLedgerEntry {
        val cap = ArgumentCaptor.forClass(RevenueLedgerEntry::class.java)
        verify(ledger).save(cap.capture() ?: RevenueLedgerEntry(sessionId = 0L, sector = "", amount = BigDecimal.ZERO, currency = "", earnedAt = Instant.EPOCH, createdAt = Instant.EPOCH))
        return cap.value
    }

    private fun anyInstantArg(): Instant {
        org.mockito.ArgumentMatchers.any(Instant::class.java)
        return Instant.EPOCH
    }
}
