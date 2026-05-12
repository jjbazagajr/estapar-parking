package com.estapar.parking.garage

import com.estapar.parking.domain.ParkingSession
import com.estapar.parking.domain.ParkingSessionRepository
import com.estapar.parking.domain.Sector
import com.estapar.parking.domain.SectorRepository
import com.estapar.parking.domain.Spot
import com.estapar.parking.domain.SpotRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class GarageServiceTest {

    private val sessions: ParkingSessionRepository = mock(ParkingSessionRepository::class.java)
    private val spots: SpotRepository = mock(SpotRepository::class.java)
    private val sectors: SectorRepository = mock(SectorRepository::class.java)
    private val fixedInstant: Instant = Instant.parse("2026-05-10T12:00:00Z")
    private val clock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
    private val service = GarageService(sessions, spots, sectors, clock, PricingPolicy())

    private val anyTime: LocalDateTime = LocalDateTime.of(2026, 5, 10, 12, 0)
    private val plate = "ABC1D23"
    private val lat = -23.561684
    private val lng = -46.655981
    private val sessionId = 42L

    @BeforeEach
    fun stubAtomicUpdatesSucceed() {
        `when`(
            sessions.markParked(
                anyLong(),
                anyInstantArg(),
                anyString(),
                anyLong(),
                anyBigDecimalArg(),
            ),
        ).thenReturn(1)
        `when`(
            sessions.markExited(
                anyLong(),
                anyInstantArg(),
                anyBigDecimalArg(),
            ),
        ).thenReturn(1)
        `when`(spots.tryOccupy(anyLong())).thenReturn(1)
    }

    @Test
    fun `given garagem aberta when register entry then salva sessao sem multiplier (definido no PARKED)`() {
        // given
        stubOpenGarage()
        stubNoOpenSession()

        // when
        service.registerEntry(plate, anyTime)

        // then
        val captor = ArgumentCaptor.forClass(ParkingSession::class.java)
        verify(sessions).save(captor.capture())
        assertEquals(plate, captor.value.licensePlate)
        assertEquals(null, captor.value.priceMultiplier)
    }

    @Test
    fun `given placa com sessao aberta when register entry then lanca SessionAlreadyOpenException`() {
        // given
        stubOpenGarage()
        `when`(sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate))
            .thenReturn(openSession())

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
        stubNoOpenSession()

        // when
        service.registerEntry(plate, anyTime)

        // then
        verify(sessions).save(org.mockito.ArgumentMatchers.any(ParkingSession::class.java))
    }

    @Test
    fun `given placa sem sessao aberta when park vehicle then lanca SessionNotFoundException`() {
        // given
        stubNoOpenSession()

        // when / then
        assertFailsWith<SessionNotFoundException> { service.parkVehicle(plate, lat, lng) }
    }

    @Test
    fun `given markParked retorna 0 (sessao ja parqueada) when park vehicle then lanca SessionAlreadyParkedException`() {
        // given
        stubParkScenario(currentlyOccupied = 0)
        `when`(
            sessions.markParked(
                anyLong(),
                anyInstantArg(),
                anyString(),
                anyLong(),
                anyBigDecimalArg(),
            ),
        ).thenReturn(0)

        // when / then
        assertFailsWith<SessionAlreadyParkedException> { service.parkVehicle(plate, lat, lng) }
    }

    @Test
    fun `given coordenadas sem vaga when park vehicle then lanca SpotNotFoundException`() {
        // given
        `when`(sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate))
            .thenReturn(openSession())
        `when`(spots.findFirstByLatAndLng(lat, lng)).thenReturn(null)

        // when / then
        assertFailsWith<SpotNotFoundException> { service.parkVehicle(plate, lat, lng) }
    }

    @Test
    fun `given vaga ja ocupada when park vehicle then lanca SpotAlreadyOccupiedException`() {
        // given
        `when`(sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate))
            .thenReturn(openSession())
        `when`(spots.findFirstByLatAndLng(lat, lng))
            .thenReturn(spotAt(id = 7, sector = "A", occupied = true))

        // when / then
        assertFailsWith<SpotAlreadyOccupiedException> { service.parkVehicle(plate, lat, lng) }
    }

    @Test
    fun `given setor da vaga fechado no horario when park vehicle then lanca SectorClosedException`() {
        // given
        stubParkScenario(sectorOpen = LocalTime.of(14, 0), sectorClose = LocalTime.of(18, 0))

        // when / then
        assertFailsWith<SectorClosedException> { service.parkVehicle(plate, lat, lng) }
    }

    @Test
    fun `given setor referenciado pelo spot ausente when park vehicle then lanca SectorMissingException`() {
        // given
        `when`(sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate))
            .thenReturn(openSession())
        `when`(spots.findFirstByLatAndLng(lat, lng))
            .thenReturn(spotAt(id = 7, sector = "A"))
        `when`(sectors.findByNameForUpdate("A")).thenReturn(null)

        // when / then
        assertFailsWith<SectorMissingException> { service.parkVehicle(plate, lat, lng) }
    }

    @Test
    fun `given setor cheio (occupied == maxCapacity) when park vehicle then lanca SectorFullException`() {
        // given
        stubParkScenario(maxCapacity = 10, currentlyOccupied = 10)

        // when / then
        assertFailsWith<SectorFullException> { service.parkVehicle(plate, lat, lng) }
        verify(spots, never()).tryOccupy(anyLong())
    }

    @Test
    fun `given tryOccupy retorna 0 (corrida cross-session) when park vehicle then lanca SpotAlreadyOccupiedException`() {
        // given
        stubParkScenario(currentlyOccupied = 5)
        `when`(spots.tryOccupy(7L)).thenReturn(0)

        // when / then
        assertFailsWith<SpotAlreadyOccupiedException> { service.parkVehicle(plate, lat, lng) }
        verify(sessions, never()).markParked(anyLong(), anyInstantArg(), anyString(), anyLong(), anyBigDecimalArg())
    }

    @Test
    fun `given sessao valida e vaga livre when park vehicle then chama tryOccupy na vaga e markParked com multiplier do setor`() {
        // given
        stubParkScenario(maxCapacity = 10, currentlyOccupied = 2)

        // when
        service.parkVehicle(plate, lat, lng)

        // then
        verify(spots).tryOccupy(7L)
        val call = capturedMarkParkedCall()
        assertEquals(sessionId, call.id)
        assertEquals("A", call.sector)
        assertEquals(7L, call.spotId)
        assertEquals(fixedInstant, call.parkedTime)
        assertEquals(0, BigDecimal("0.90").compareTo(call.multiplier))
        verify(sessions, never()).save(org.mockito.ArgumentMatchers.any(ParkingSession::class.java))
    }

    @Test
    fun `given setor com 0 porcento ocupacao when park vehicle then markParked com multiplier 0_90`() {
        // given
        stubParkScenario(maxCapacity = 10, currentlyOccupied = 0)

        // when
        service.parkVehicle(plate, lat, lng)

        // then
        assertEquals(0, BigDecimal("0.90").compareTo(capturedMarkParkedCall().multiplier))
    }

    @Test
    fun `given setor com 49 porcento ocupacao when park vehicle then markParked com multiplier 1_00`() {
        // given
        stubParkScenario(maxCapacity = 100, currentlyOccupied = 49)

        // when
        service.parkVehicle(plate, lat, lng)

        // then
        assertEquals(0, BigDecimal("1.00").compareTo(capturedMarkParkedCall().multiplier))
    }

    @Test
    fun `given setor com 74 porcento ocupacao when park vehicle then markParked com multiplier 1_10`() {
        // given
        stubParkScenario(maxCapacity = 100, currentlyOccupied = 74)

        // when
        service.parkVehicle(plate, lat, lng)

        // then
        assertEquals(0, BigDecimal("1.10").compareTo(capturedMarkParkedCall().multiplier))
    }

    @Test
    fun `given setor com 99 porcento ocupacao when park vehicle then markParked com multiplier 1_25`() {
        // given
        stubParkScenario(maxCapacity = 100, currentlyOccupied = 99)

        // when
        service.parkVehicle(plate, lat, lng)

        // then
        assertEquals(0, BigDecimal("1.25").compareTo(capturedMarkParkedCall().multiplier))
    }

    @Test
    fun `given setor sem open hour e close hour when park vehicle then aceita`() {
        // given
        stubParkScenario(sectorOpen = null, sectorClose = null)

        // when
        service.parkVehicle(plate, lat, lng)

        // then
        verify(spots).tryOccupy(7L)
    }

    @Test
    fun `given placa sem sessao aberta when process exit then lanca SessionNotFoundException`() {
        // given
        stubNoOpenSession()

        // when / then
        assertFailsWith<SessionNotFoundException> { service.processExit(plate, anyTime) }
    }

    @Test
    fun `given sessao sem spot vinculado when process exit then lanca SessionNotParkedException`() {
        // given
        stubSession(openSession())

        // when / then
        assertFailsWith<SessionNotParkedException> { service.processExit(plate, anyTime.plusHours(1)) }
    }

    @Test
    fun `given sessao sem sector vinculado when process exit then lanca SessionNotParkedException`() {
        // given (defensivo — inalcançavel pelo parkVehicle real, que seta spotId e sector juntos)
        val session = openSession().apply {
            spotId = 7L
            sector = null
        }
        stubSession(session)

        // when / then
        assertFailsWith<SessionNotParkedException> { service.processExit(plate, anyTime.plusHours(1)) }
    }

    @Test
    fun `given setor da sessao removido do banco when process exit then lanca SectorMissingException`() {
        // given
        stubSession(parkedSession(anyTime))
        stubSpot(7L, spotAt(id = 7, sector = "A", occupied = true))
        `when`(sectors.findByName("A")).thenReturn(null)

        // when / then
        assertFailsWith<SectorMissingException> { service.processExit(plate, anyTime.plusHours(1)) }
    }

    @Test
    fun `given spot da sessao removido do banco when process exit then lanca IllegalStateException`() {
        // given
        stubSession(parkedSession(anyTime))
        `when`(spots.findById(7L)).thenReturn(Optional.empty())

        // when / then
        assertFailsWith<IllegalStateException> { service.processExit(plate, anyTime.plusHours(1)) }
    }

    @Test
    fun `given exit antes de entry when process exit then lanca IllegalStateException`() {
        // given
        stubSession(parkedSession(anyTime))

        // when / then
        assertFailsWith<IllegalStateException> { service.processExit(plate, anyTime.minusSeconds(1)) }
    }

    @Test
    fun `given duracao 0 min when process exit then amount 0_00 e libera spot`() {
        // given
        val spot = spotAt(id = 7, sector = "A", occupied = true)
        val session = parkedSession(anyTime)
        stubSession(session)
        stubSpot(7L, spot)
        stubSector("A", BigDecimal("40.50"))

        // when
        service.processExit(plate, anyTime)

        // then
        assertEquals(BigDecimal("0.00"), capturedExitedAmount())
        assertEquals(false, spot.occupied)
    }

    @Test
    fun `given duracao exata 30 min when process exit then amount 0_00 e libera spot`() {
        // given
        val spot = spotAt(id = 7, sector = "A", occupied = true)
        val session = parkedSession(anyTime)
        stubSession(session)
        stubSpot(7L, spot)
        stubSector("A", BigDecimal("40.50"))

        // when
        service.processExit(plate, anyTime.plusMinutes(30))

        // then
        assertEquals(BigDecimal("0.00"), capturedExitedAmount())
        assertEquals(false, spot.occupied)
    }

    @Test
    fun `given duracao 30 min e 1 segundo when process exit then amount cobra 1 hora`() {
        // given
        val session = parkedSession(anyTime)
        stubSession(session)
        stubSpot(7L, spotAt(id = 7, sector = "A", occupied = true))
        stubSector("A", BigDecimal("40.50"))

        // when
        service.processExit(plate, anyTime.plusMinutes(30).plusSeconds(1))

        // then 1 × 40.50 × 1.000 = 40.50
        assertEquals(BigDecimal("40.50"), capturedExitedAmount())
    }

    @Test
    fun `given duracao 31 min when process exit then amount cobra 1 hora`() {
        // given
        val session = parkedSession(anyTime)
        stubSession(session)
        stubSpot(7L, spotAt(id = 7, sector = "A", occupied = true))
        stubSector("A", BigDecimal("40.50"))

        // when
        service.processExit(plate, anyTime.plusMinutes(31))

        // then
        assertEquals(BigDecimal("40.50"), capturedExitedAmount())
    }

    @Test
    fun `given duracao 59 min when process exit then amount cobra 1 hora`() {
        // given
        val session = parkedSession(anyTime)
        stubSession(session)
        stubSpot(7L, spotAt(id = 7, sector = "A", occupied = true))
        stubSector("A", BigDecimal("40.50"))

        // when
        service.processExit(plate, anyTime.plusMinutes(59))

        // then
        assertEquals(BigDecimal("40.50"), capturedExitedAmount())
    }

    @Test
    fun `given duracao exata 60 min when process exit then amount cobra 1 hora`() {
        // given
        val session = parkedSession(anyTime)
        stubSession(session)
        stubSpot(7L, spotAt(id = 7, sector = "A", occupied = true))
        stubSector("A", BigDecimal("40.50"))

        // when
        service.processExit(plate, anyTime.plusMinutes(60))

        // then
        assertEquals(BigDecimal("40.50"), capturedExitedAmount())
    }

    @Test
    fun `given duracao 60 min e 1 segundo when process exit then amount cobra 2 horas`() {
        // given
        val session = parkedSession(anyTime)
        stubSession(session)
        stubSpot(7L, spotAt(id = 7, sector = "A", occupied = true))
        stubSector("A", BigDecimal("40.50"))

        // when
        service.processExit(plate, anyTime.plusMinutes(60).plusSeconds(1))

        // then 2 × 40.50 × 1.000 = 81.00
        assertEquals(BigDecimal("81.00"), capturedExitedAmount())
    }

    @Test
    fun `given duracao 1h 5min when process exit then amount cobra 2 horas`() {
        // given
        val session = parkedSession(anyTime)
        stubSession(session)
        stubSpot(7L, spotAt(id = 7, sector = "A", occupied = true))
        stubSector("A", BigDecimal("40.50"))

        // when
        service.processExit(plate, anyTime.plusHours(1).plusMinutes(5))

        // then
        assertEquals(BigDecimal("81.00"), capturedExitedAmount())
    }

    @Test
    fun `given duracao exata 2h when process exit then amount cobra 2 horas`() {
        // given
        val session = parkedSession(anyTime)
        stubSession(session)
        stubSpot(7L, spotAt(id = 7, sector = "A", occupied = true))
        stubSector("A", BigDecimal("40.50"))

        // when
        service.processExit(plate, anyTime.plusHours(2))

        // then
        assertEquals(BigDecimal("81.00"), capturedExitedAmount())
    }

    @Test
    fun `given basePrice 40_50 multiplicador 0_900 duracao 1h when process exit then amount 36_45`() {
        // given
        val session = parkedSession(anyTime, multiplier = BigDecimal("0.900"))
        stubSession(session)
        stubSpot(7L, spotAt(id = 7, sector = "A", occupied = true))
        stubSector("A", BigDecimal("40.50"))

        // when
        service.processExit(plate, anyTime.plusHours(1))

        // then 1 × 40.50 × 0.900 = 36.4500 → 36.45
        assertEquals(BigDecimal("36.45"), capturedExitedAmount())
    }

    @Test
    fun `given basePrice 4_10 multiplicador 1_250 duracao 2h when process exit then amount 10_25`() {
        // given
        val session = parkedSession(anyTime, sectorName = "B", multiplier = BigDecimal("1.250"))
        stubSession(session)
        stubSpot(7L, spotAt(id = 7, sector = "B", occupied = true))
        stubSector("B", BigDecimal("4.10"))

        // when
        service.processExit(plate, anyTime.plusHours(2))

        // then 2 × 4.10 × 1.250 = 10.2500 → 10.25
        assertEquals(BigDecimal("10.25"), capturedExitedAmount())
    }

    @Test
    fun `given basePrice 40_50 multiplicador 1_100 duracao 25h when process exit then amount 1113_75`() {
        // given
        val session = parkedSession(anyTime, multiplier = BigDecimal("1.100"))
        stubSession(session)
        stubSpot(7L, spotAt(id = 7, sector = "A", occupied = true))
        stubSector("A", BigDecimal("40.50"))

        // when
        service.processExit(plate, anyTime.plusHours(25))

        // then 25 × 40.50 × 1.100 = 1113.7500 → 1113.75
        assertEquals(BigDecimal("1113.75"), capturedExitedAmount())
    }

    @Test
    fun `given exit valido when process exit then libera spot definido pela sessao`() {
        // given
        val spot = spotAt(id = 7, sector = "A", occupied = true)
        stubSession(parkedSession(anyTime))
        stubSpot(7L, spot)
        stubSector("A", BigDecimal("40.50"))

        // when
        service.processExit(plate, anyTime.plusHours(1))

        // then
        assertEquals(false, spot.occupied)
        verify(spots, never()).save(org.mockito.ArgumentMatchers.any(Spot::class.java))
    }

    @Test
    fun `given exit valido when process exit then chama markExited com exit_time e amount_charged`() {
        // given
        stubSession(parkedSession(anyTime))
        stubSpot(7L, spotAt(id = 7, sector = "A", occupied = true))
        stubSector("A", BigDecimal("40.50"))

        // when
        val exit = anyTime.plusHours(1)
        service.processExit(plate, exit)

        // then
        val call = capturedMarkExitedCall()
        assertEquals(sessionId, call.id)
        assertEquals(exit.toInstant(ZoneOffset.UTC), call.exitTime)
        assertEquals(BigDecimal("40.50"), call.amount)
        verify(sessions, never()).save(org.mockito.ArgumentMatchers.any(ParkingSession::class.java))
    }

    @Test
    fun `given markExited retorna 0 (sessao ja encerrada) when process exit then lanca SessionAlreadyExitedException e nao libera a vaga`() {
        // given
        val spot = spotAt(id = 7, sector = "A", occupied = true)
        stubSession(parkedSession(anyTime))
        stubSpot(7L, spot)
        stubSector("A", BigDecimal("40.50"))
        `when`(
            sessions.markExited(
                anyLong(),
                anyInstantArg(),
                anyBigDecimalArg(),
            ),
        ).thenReturn(0)

        // when / then
        assertFailsWith<SessionAlreadyExitedException> {
            service.processExit(plate, anyTime.plusHours(1))
        }
        assertEquals(true, spot.occupied)
    }

    private fun stubOpenGarage() {
        `when`(sectors.findAll()).thenReturn(
            listOf(sectorOpenBetween("A", LocalTime.of(0, 0), LocalTime.of(23, 59))),
        )
    }

    private fun stubNoOpenSession() {
        `when`(sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate))
            .thenReturn(null)
    }

    private fun stubParkScenario(
        sectorOpen: LocalTime? = null,
        sectorClose: LocalTime? = null,
        maxCapacity: Int = 10,
        currentlyOccupied: Long = 0,
    ) {
        `when`(sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate))
            .thenReturn(openSession())
        `when`(spots.findFirstByLatAndLng(lat, lng))
            .thenReturn(spotAt(id = 7, sector = "A"))
        `when`(sectors.findByNameForUpdate("A"))
            .thenReturn(sectorOpenBetween("A", sectorOpen, sectorClose, maxCapacity))
        `when`(spots.countBySectorAndOccupiedTrue("A")).thenReturn(currentlyOccupied)
    }

    private fun openSession() = ParkingSession(
        id = sessionId,
        licensePlate = plate,
        entryTime = anyTime.toInstant(ZoneOffset.UTC),
        priceMultiplier = BigDecimal("1.00"),
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

    private fun sectorWithBasePrice(name: String, basePrice: BigDecimal) = Sector(
        name = name,
        basePrice = basePrice,
        maxCapacity = 10,
        openHour = null,
        closeHour = null,
    )

    private fun stubSession(session: ParkingSession) {
        `when`(sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate))
            .thenReturn(session)
    }

    private fun stubSpot(spotId: Long, spot: Spot) {
        `when`(spots.findById(spotId)).thenReturn(Optional.of(spot))
    }

    private fun stubSector(name: String, basePrice: BigDecimal) {
        `when`(sectors.findByName(name)).thenReturn(sectorWithBasePrice(name, basePrice))
    }

    private data class MarkParkedCall(
        val id: Long,
        val parkedTime: Instant,
        val sector: String,
        val spotId: Long,
        val multiplier: BigDecimal,
    )

    private fun capturedMarkParkedCall(): MarkParkedCall {
        val idCap = ArgumentCaptor.forClass(Long::class.javaObjectType)
        val timeCap = ArgumentCaptor.forClass(Instant::class.java)
        val sectorCap = ArgumentCaptor.forClass(String::class.java)
        val spotCap = ArgumentCaptor.forClass(Long::class.javaObjectType)
        val multCap = ArgumentCaptor.forClass(BigDecimal::class.java)
        verify(sessions).markParked(
            idCap.capture() ?: 0L,
            timeCap.capture() ?: Instant.EPOCH,
            sectorCap.capture() ?: "",
            spotCap.capture() ?: 0L,
            multCap.capture() ?: BigDecimal.ZERO,
        )
        return MarkParkedCall(idCap.value, timeCap.value, sectorCap.value, spotCap.value, multCap.value)
    }

    private data class MarkExitedCall(
        val id: Long,
        val exitTime: Instant,
        val amount: BigDecimal,
    )

    private fun capturedMarkExitedCall(): MarkExitedCall {
        val idCap = ArgumentCaptor.forClass(Long::class.javaObjectType)
        val timeCap = ArgumentCaptor.forClass(Instant::class.java)
        val amountCap = ArgumentCaptor.forClass(BigDecimal::class.java)
        verify(sessions).markExited(
            idCap.capture() ?: 0L,
            timeCap.capture() ?: Instant.EPOCH,
            amountCap.capture() ?: BigDecimal.ZERO,
        )
        return MarkExitedCall(idCap.value, timeCap.value, amountCap.value)
    }

    private fun capturedExitedAmount(): BigDecimal = capturedMarkExitedCall().amount

    private fun anyInstantArg(): Instant {
        org.mockito.ArgumentMatchers.any(Instant::class.java)
        return Instant.EPOCH
    }

    private fun anyBigDecimalArg(): BigDecimal {
        org.mockito.ArgumentMatchers.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }
}
