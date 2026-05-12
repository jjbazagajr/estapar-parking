package com.estapar.parking.webhook

import com.estapar.parking.config.SyncAsyncTestConfig
import com.estapar.parking.domain.Sector
import com.estapar.parking.domain.Spot
import com.estapar.parking.sector.SectorRepository
import com.estapar.parking.session.ParkingSessionRepository
import com.estapar.parking.spot.SpotRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(SyncAsyncTestConfig::class)
@ActiveProfiles("sync-async")
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exit-it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "estapar.simulator.bootstrap-enabled=false",
    ],
)
class WebhookFlowIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var sectors: SectorRepository

    @Autowired
    private lateinit var spots: SpotRepository

    @Autowired
    private lateinit var sessions: ParkingSessionRepository

    private val plate = "ABC1D23"
    private val spotLat = -23.561684
    private val spotLng = -46.655981

    @BeforeEach
    fun setup() {
        sessions.deleteAll()
        spots.deleteAll()
        sectors.deleteAll()
        sectors.save(
            Sector(
                name = "A",
                basePrice = BigDecimal("40.50"),
                maxCapacity = 10,
                openHour = null,
                closeHour = null,
            ),
        )
        spots.save(
            Spot(id = 1L, sector = "A", lat = spotLat, lng = spotLng, occupied = false),
        )
    }

    @Test
    fun `given garagem com setor A e uma vaga when fluxo completo ENTRY PARKED EXIT then session fechada e spot liberado`() {
        // given (setup pré-popula sector A + 1 spot)

        // when ENTRY
        mockMvc.post("/webhook") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"event_type":"ENTRY","license_plate":"$plate","entry_time":"2026-05-10T12:00:00"}"""
        }.andExpect {
            status { isOk() }
        }

        // then ENTRY persistiu sessão sem multiplicador (definido no PARKED)
        val afterEntry = sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate)
        assertNotNull(afterEntry)
        assertNull(afterEntry.priceMultiplier)
        assertNull(afterEntry.spotId)
        assertNull(afterEntry.exitTime)

        // when PARKED
        mockMvc.post("/webhook") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"event_type":"PARKED","license_plate":"$plate","lat":$spotLat,"lng":$spotLng}"""
        }.andExpect {
            status { isOk() }
        }

        // then PARKED vinculou vaga, marcou ocupada e congelou multiplicador do setor (0/1 -> 0.900)
        val afterParked = sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate)
        assertNotNull(afterParked)
        assertEquals(1L, afterParked.spotId)
        assertEquals("A", afterParked.sector)
        assertNotNull(afterParked.parkedTime)
        assertEquals(0, BigDecimal("0.900").compareTo(afterParked.priceMultiplier))
        assertEquals(true, spots.findById(1L).orElseThrow().occupied)

        // when EXIT 1 hora depois
        mockMvc.post("/webhook") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"event_type":"EXIT","license_plate":"$plate","exit_time":"2026-05-10T13:00:00"}"""
        }.andExpect {
            status { isOk() }
        }

        // then EXIT gravou exit_time e liberou vaga (receita coberta em RevenueFlowIntegrationTest)
        val afterExit = sessions.findAll().single { it.licensePlate == plate }
        assertNotNull(afterExit.exitTime)
        assertEquals(false, spots.findById(1L).orElseThrow().occupied)
        assertTrue(sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate) == null)
    }

    @Test
    fun `given placa com ENTRY sem PARKED when EXIT then responde 200 e mantem session aberta`() {
        // given (setup pré-popula sector A + 1 spot)
        mockMvc.post("/webhook") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"event_type":"ENTRY","license_plate":"$plate","entry_time":"2026-05-10T12:00:00"}"""
        }.andExpect {
            status { isOk() }
        }

        // when EXIT sem PARKED prévio
        mockMvc.post("/webhook") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"event_type":"EXIT","license_plate":"$plate","exit_time":"2026-05-10T12:10:00"}"""
        }.andExpect {
            status { isOk() }
        }

        // then sessão permanece aberta (não fechada nem estacionada)
        val open = sessions.findFirstByLicensePlateAndExitTimeIsNullOrderByEntryTimeDesc(plate)
        assertNotNull(open)
        assertNull(open.exitTime)
        assertNull(open.spotId)
    }

    @Test
    fun `given placa com sessao aberta when novo ENTRY then responde 200 e nao duplica sessao`() {
        // given primeira ENTRY
        mockMvc.post("/webhook") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"event_type":"ENTRY","license_plate":"$plate","entry_time":"2026-05-10T12:00:00"}"""
        }.andExpect {
            status { isOk() }
        }

        // when segunda ENTRY com a sessão original ainda aberta
        mockMvc.post("/webhook") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"event_type":"ENTRY","license_plate":"$plate","entry_time":"2026-05-10T12:05:00"}"""
        }.andExpect {
            status { isOk() }
        }

        // then continua apenas uma sessão aberta para a placa
        val abertas = sessions.findAll().filter { it.licensePlate == plate && it.exitTime == null }
        assertEquals(1, abertas.size)
    }
}
