package com.estapar.parking.simulator

import com.estapar.parking.config.SimulatorProperties
import com.estapar.parking.domain.Sector
import com.estapar.parking.domain.Spot
import com.estapar.parking.sector.SectorService
import com.estapar.parking.spot.SpotService
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GarageBootstrapTest {

    private val sectorService: SectorService = mock(SectorService::class.java)
    private val spotService: SpotService = mock(SpotService::class.java)
    private val restClient: RestClient = mock(RestClient::class.java)
    private val properties = SimulatorProperties(baseUrl = "http://test", bootstrapEnabled = true)
    private val bootstrap = GarageBootstrap(restClient, sectorService, spotService, properties)

    @Test
    fun `given sector ausente quando persist entao insere novo registro`() {
        // given
        val dto = SectorDto(
            sector = "A",
            basePrice = BigDecimal("50.00"),
            maxCapacity = 10,
            openHour = "00:00",
            closeHour = "23:59",
            durationLimitMinutes = 1440,
        )
        `when`(sectorService.findByName("A")).thenReturn(null)

        // when
        bootstrap.persist(GarageResponse(garage = listOf(dto), spots = emptyList()))

        // then
        val captor = ArgumentCaptor.forClass(Sector::class.java)
        verify(sectorService).save(captor.capture() ?: Sector(name = "", basePrice = BigDecimal.ZERO, maxCapacity = 0))
        val saved = captor.value
        assertNull(saved.id)
        assertEquals("A", saved.name)
        assertEquals(BigDecimal("50.00"), saved.basePrice)
        assertEquals(10, saved.maxCapacity)
        assertEquals(LocalTime.of(0, 0), saved.openHour)
        assertEquals(LocalTime.of(23, 59), saved.closeHour)
        assertEquals(1440, saved.durationLimitMinutes)
    }

    @Test
    fun `given sector ja cadastrado quando persist entao atualiza preservando id`() {
        // given
        val existing = Sector(
            id = 7L,
            name = "A",
            basePrice = BigDecimal("10.00"),
            maxCapacity = 5,
        )
        val dto = SectorDto(
            sector = "A",
            basePrice = BigDecimal("99.99"),
            maxCapacity = 25,
            openHour = "08:00",
        )
        `when`(sectorService.findByName("A")).thenReturn(existing)

        // when
        bootstrap.persist(GarageResponse(garage = listOf(dto), spots = emptyList()))

        // then
        val captor = ArgumentCaptor.forClass(Sector::class.java)
        verify(sectorService).save(captor.capture() ?: Sector(name = "", basePrice = BigDecimal.ZERO, maxCapacity = 0))
        val saved = captor.value
        assertEquals(7L, saved.id)
        assertEquals("A", saved.name)
        assertEquals(BigDecimal("99.99"), saved.basePrice)
        assertEquals(25, saved.maxCapacity)
        assertEquals(LocalTime.of(8, 0), saved.openHour)
    }

    @Test
    fun `given spot ausente quando persist entao insere com id do simulador`() {
        // given
        val dto = SpotDto(
            id = 42L,
            sector = "A",
            lat = -23.5,
            lng = -46.6,
            occupied = false,
        )
        `when`(spotService.findById(42L)).thenReturn(null)

        // when
        bootstrap.persist(GarageResponse(garage = emptyList(), spots = listOf(dto)))

        // then
        val captor = ArgumentCaptor.forClass(Spot::class.java)
        verify(spotService).save(captor.capture() ?: Spot(id = 0L, sector = "", lat = 0.0, lng = 0.0))
        val saved = captor.value
        assertEquals(42L, saved.id)
        assertEquals("A", saved.sector)
        assertEquals(-23.5, saved.lat)
        assertEquals(-46.6, saved.lng)
        assertEquals(false, saved.occupied)
    }

    @Test
    fun `given spot ja cadastrado quando persist entao atualiza sem chamar saveAll`() {
        // given
        val existing = Spot(
            id = 42L,
            sector = "A",
            lat = -23.5,
            lng = -46.6,
            occupied = true,
        )
        val dto = SpotDto(
            id = 42L,
            sector = "B",
            lat = -10.0,
            lng = -20.0,
            occupied = false,
        )
        `when`(spotService.findById(42L)).thenReturn(existing)

        // when
        bootstrap.persist(GarageResponse(garage = emptyList(), spots = listOf(dto)))

        // then
        val captor = ArgumentCaptor.forClass(Spot::class.java)
        verify(spotService).findById(42L)
        verify(spotService).save(captor.capture() ?: Spot(id = 0L, sector = "", lat = 0.0, lng = 0.0))
        verifyNoMoreInteractions(spotService)
        val saved = captor.value
        assertEquals(42L, saved.id)
        assertEquals("B", saved.sector)
        assertEquals(-10.0, saved.lat)
        assertEquals(-20.0, saved.lng)
        assertEquals(false, saved.occupied)
    }
}
