package com.estapar.parking.garage

import com.estapar.parking.domain.GarageClosedException
import com.estapar.parking.domain.ParkingSession
import com.estapar.parking.domain.Sector
import com.estapar.parking.domain.SectorClosedException
import com.estapar.parking.domain.SectorFullException
import com.estapar.parking.domain.SectorMissingException
import com.estapar.parking.domain.SessionAlreadyExitedException
import com.estapar.parking.domain.SessionNotFoundException
import com.estapar.parking.domain.SessionNotParkedException
import com.estapar.parking.domain.Spot
import com.estapar.parking.domain.SpotNotFoundException
import com.estapar.parking.sector.SectorService
import com.estapar.parking.session.SessionService
import com.estapar.parking.spot.SpotService
import com.estapar.parking.revenue.AddToRevenueEvent
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GarageServiceTest {

    private val sessionService: SessionService = mock(SessionService::class.java)
    private val spotService: SpotService = mock(SpotService::class.java)
    private val sectorService: SectorService = mock(SectorService::class.java)
    private val events: ApplicationEventPublisher = mock(ApplicationEventPublisher::class.java)
    private val fixedInstant: Instant = Instant.parse("2026-05-10T12:00:00Z")
    private val clock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val service = GarageService(sessionService, spotService, sectorService, clock, PricingPolicy(), events)

    private val anyTime: LocalDateTime = LocalDateTime.of(2026, 5, 10, 12, 0)
    private val plate = "ABC1D23"
    private val lat = -23.561684
    private val lng = -46.655981
    private val sessionId = 42L

    @Test
    fun `given garagem aberta when register entry then delega para sessionService_openByPlate`() {
        // given
        `when`(sectorService.isAnyOpenAt(any(LocalTime::class.java) ?: LocalTime.MIN)).thenReturn(true)

        // when
        service.registerEntry(plate, anyTime)

        // then
        verify(sessionService).openByPlate(plate, anyTime.toInstant(ZoneOffset.UTC))
    }

    @Test
    fun `given garagem fechada when register entry then lanca GarageClosedException e nao abre sessao`() {
        // given
        `when`(sectorService.isAnyOpenAt(any(LocalTime::class.java) ?: LocalTime.MIN)).thenReturn(false)

        // when / then
        assertFailsWith<GarageClosedException> { service.registerEntry(plate, anyTime) }
        verify(sessionService, never()).openByPlate(anyString(), any(Instant::class.java) ?: Instant.EPOCH)
    }

    @Test
    fun `given placa sem sessao aberta when park vehicle then lanca SessionNotFoundException`() {
        // given
        `when`(sessionService.findOpenByPlate(plate)).thenReturn(null)

        // when / then
        assertFailsWith<SessionNotFoundException> { service.parkVehicle(plate, lat, lng) }
    }

    @Test
    fun `given coordenadas sem vaga when park vehicle then propaga SpotNotFoundException do service`() {
        // given
        `when`(sessionService.findOpenByPlate(plate)).thenReturn(openSession())
        `when`(spotService.findByCoordinates(lat, lng)).thenThrow(SpotNotFoundException(lat, lng))

        // when / then
        assertFailsWith<SpotNotFoundException> { service.parkVehicle(plate, lat, lng) }
    }

    @Test
    fun `given setor referenciado pelo spot ausente when park vehicle then lanca SectorMissingException`() {
        // given
        `when`(sessionService.findOpenByPlate(plate)).thenReturn(openSession())
        `when`(spotService.findByCoordinates(lat, lng)).thenReturn(spotAt(7L, "A"))
        `when`(sectorService.lockByName("A")).thenReturn(null)

        // when / then
        assertFailsWith<SectorMissingException> { service.parkVehicle(plate, lat, lng) }
    }

    @Test
    fun `given setor da vaga fechado no horario when park vehicle then lanca SectorClosedException`() {
        // given
        stubParkScenario(sectorOpen = LocalTime.of(14, 0), sectorClose = LocalTime.of(18, 0))

        // when / then
        assertFailsWith<SectorClosedException> { service.parkVehicle(plate, lat, lng) }
    }

    @Test
    fun `given setor cheio (occupied == maxCapacity) when park vehicle then lanca SectorFullException e nao ocupa vaga`() {
        // given
        stubParkScenario(maxCapacity = 10, currentlyOccupied = 10)

        // when / then
        assertFailsWith<SectorFullException> { service.parkVehicle(plate, lat, lng) }
        verify(spotService, never()).occupy(any(Spot::class.java) ?: Spot(id = 0L, sector = "", lat = 0.0, lng = 0.0))
    }

    @Test
    fun `given sessao valida e vaga livre when park vehicle then ocupa vaga e marca sessao parked com multiplier do setor`() {
        // given
        stubParkScenario(maxCapacity = 10, currentlyOccupied = 2)

        // when
        service.parkVehicle(plate, lat, lng)

        // then
        verify(spotService).occupy(any(Spot::class.java) ?: Spot(id = 0L, sector = "", lat = 0.0, lng = 0.0))
        val multCap = ArgumentCaptor.forClass(BigDecimal::class.java)
        verify(sessionService).markParked(
            any(ParkingSession::class.java) ?: openSession(),
            eqInstant(fixedInstant),
            eqString("A"),
            eqLong(7L),
            multCap.capture() ?: BigDecimal.ZERO,
        )
        assertEquals(0, BigDecimal("0.90").compareTo(multCap.value))
    }

    @Test
    fun `given setor com 0 porcento ocupacao when park vehicle then markParked com multiplier 0_90`() {
        stubParkScenario(maxCapacity = 10, currentlyOccupied = 0)
        service.parkVehicle(plate, lat, lng)
        assertEquals(0, BigDecimal("0.90").compareTo(capturedMultiplier()))
    }

    @Test
    fun `given setor com 49 porcento ocupacao when park vehicle then markParked com multiplier 1_00`() {
        stubParkScenario(maxCapacity = 100, currentlyOccupied = 49)
        service.parkVehicle(plate, lat, lng)
        assertEquals(0, BigDecimal("1.00").compareTo(capturedMultiplier()))
    }

    @Test
    fun `given setor com 74 porcento ocupacao when park vehicle then markParked com multiplier 1_10`() {
        stubParkScenario(maxCapacity = 100, currentlyOccupied = 74)
        service.parkVehicle(plate, lat, lng)
        assertEquals(0, BigDecimal("1.10").compareTo(capturedMultiplier()))
    }

    @Test
    fun `given setor com 99 porcento ocupacao when park vehicle then markParked com multiplier 1_25`() {
        stubParkScenario(maxCapacity = 100, currentlyOccupied = 99)
        service.parkVehicle(plate, lat, lng)
        assertEquals(0, BigDecimal("1.25").compareTo(capturedMultiplier()))
    }

    @Test
    fun `given placa sem sessao aberta when process exit then lanca SessionNotFoundException`() {
        // given
        `when`(sessionService.findOpenByPlate(plate)).thenReturn(null)

        // when / then
        assertFailsWith<SessionNotFoundException> { service.processExit(plate, anyTime) }
    }

    @Test
    fun `given sessao sem spot vinculado when process exit then lanca SessionNotParkedException`() {
        // given
        `when`(sessionService.findOpenByPlate(plate)).thenReturn(openSession())

        // when / then
        assertFailsWith<SessionNotParkedException> { service.processExit(plate, anyTime.plusHours(1)) }
    }

    @Test
    fun `given spot da sessao removido do banco when process exit then lanca IllegalStateException`() {
        // given
        `when`(sessionService.findOpenByPlate(plate)).thenReturn(parkedSession(anyTime))
        `when`(spotService.findById(7L)).thenReturn(null)

        // when / then
        assertFailsWith<IllegalStateException> { service.processExit(plate, anyTime.plusHours(1)) }
    }

    @Test
    fun `given exit valido when process exit then marca exited, libera vaga e publica AddToRevenueEvent`() {
        // given
        val session = parkedSession(anyTime)
        val spot = spotAt(7L, "A", occupied = true)
        `when`(sessionService.findOpenByPlate(plate)).thenReturn(session)
        `when`(spotService.findById(7L)).thenReturn(spot)

        // when
        val exit = anyTime.plusHours(1)
        service.processExit(plate, exit)

        // then
        verify(sessionService).markExited(session, exit.toInstant(ZoneOffset.UTC))
        verify(spotService).release(spot)
        val cap = ArgumentCaptor.forClass(AddToRevenueEvent::class.java)
        verify(events).publishEvent(cap.capture() ?: AddToRevenueEvent(0L, Instant.EPOCH))
        assertEquals(sessionId, cap.value.sessionId)
        assertEquals(exit.toInstant(ZoneOffset.UTC), cap.value.exitTime)
    }

    @Test
    fun `given sessionService_markExited lanca SessionAlreadyExitedException when process exit then propaga e nao publica evento`() {
        // given
        val session = parkedSession(anyTime)
        `when`(sessionService.findOpenByPlate(plate)).thenReturn(session)
        `when`(spotService.findById(7L)).thenReturn(spotAt(7L, "A", occupied = true))
        doThrow(SessionAlreadyExitedException(plate))
            .`when`(sessionService).markExited(any(ParkingSession::class.java) ?: session, any(Instant::class.java) ?: Instant.EPOCH)

        // when / then
        assertFailsWith<SessionAlreadyExitedException> {
            service.processExit(plate, anyTime.plusHours(1))
        }
        verify(events, never()).publishEvent(any(AddToRevenueEvent::class.java) ?: AddToRevenueEvent(0L, Instant.EPOCH))
    }

    private fun stubParkScenario(
        sectorOpen: LocalTime? = null,
        sectorClose: LocalTime? = null,
        maxCapacity: Int = 10,
        currentlyOccupied: Long = 0,
    ) {
        `when`(sessionService.findOpenByPlate(plate)).thenReturn(openSession())
        `when`(spotService.findByCoordinates(lat, lng)).thenReturn(spotAt(7L, "A"))
        `when`(sectorService.lockByName("A"))
            .thenReturn(sectorOpenBetween("A", sectorOpen, sectorClose, maxCapacity))
        `when`(spotService.countOccupiedIn("A")).thenReturn(currentlyOccupied)
    }

    private fun openSession() = ParkingSession(
        id = sessionId,
        licensePlate = plate,
        entryTime = anyTime.toInstant(ZoneOffset.UTC),
    )

    private fun spotAt(id: Long, sector: String, occupied: Boolean = false) = Spot(
        id = id,
        sector = sector,
        lat = lat,
        lng = lng,
        occupied = occupied,
    )

    private fun sectorOpenBetween(name: String, open: LocalTime?, close: LocalTime?, maxCapacity: Int = 10) = Sector(
        name = name,
        basePrice = BigDecimal("10.00"),
        maxCapacity = maxCapacity,
        openHour = open,
        closeHour = close,
    )

    private fun parkedSession(
        entry: LocalDateTime,
        spotId: Long? = 7L,
        sectorName: String? = "A",
        multiplier: BigDecimal = BigDecimal("1.000"),
    ) = ParkingSession(
        id = sessionId,
        licensePlate = plate,
        entryTime = entry.toInstant(ZoneOffset.UTC),
        priceMultiplier = multiplier,
        sector = sectorName,
        spotId = spotId,
        parkedTime = entry.toInstant(ZoneOffset.UTC),
    )

    private fun capturedMultiplier(): BigDecimal {
        val multCap = ArgumentCaptor.forClass(BigDecimal::class.java)
        verify(sessionService).markParked(
            any(ParkingSession::class.java) ?: openSession(),
            any(Instant::class.java) ?: Instant.EPOCH,
            anyString(),
            anyLong(),
            multCap.capture() ?: BigDecimal.ZERO,
        )
        return multCap.value
    }

    private fun eqInstant(expected: Instant): Instant {
        org.mockito.ArgumentMatchers.eq(expected)
        return expected
    }

    private fun eqString(expected: String): String {
        org.mockito.ArgumentMatchers.eq(expected)
        return expected
    }

    private fun eqLong(expected: Long): Long {
        org.mockito.ArgumentMatchers.eq(expected)
        return expected
    }
}
