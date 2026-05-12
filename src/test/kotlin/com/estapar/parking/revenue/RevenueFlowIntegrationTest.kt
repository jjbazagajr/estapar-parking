package com.estapar.parking.revenue

import com.estapar.parking.config.SyncAsyncTestConfig
import com.estapar.parking.domain.Sector
import com.estapar.parking.domain.Spot
import com.estapar.parking.ledger.RevenueLedgerRepository
import com.estapar.parking.sector.SectorRepository
import com.estapar.parking.session.ParkingSessionRepository
import com.estapar.parking.spot.SpotRepository
import com.jayway.jsonpath.JsonPath
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(SyncAsyncTestConfig::class)
@ActiveProfiles("sync-async")
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:revenue-it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "estapar.simulator.bootstrap-enabled=false",
    ],
)
class RevenueFlowIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var sectors: SectorRepository

    @Autowired
    private lateinit var spots: SpotRepository

    @Autowired
    private lateinit var sessions: ParkingSessionRepository

    @Autowired
    private lateinit var ledger: RevenueLedgerRepository

    private val spotALat = -23.561684
    private val spotALng = -46.655981
    private val spotBLat = -23.561700
    private val spotBLng = -46.656000

    private var plateSeq = 0

    @BeforeEach
    fun setup() {
        ledger.deleteAll()
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
        sectors.save(
            Sector(
                name = "B",
                basePrice = BigDecimal("4.10"),
                maxCapacity = 10,
                openHour = null,
                closeHour = null,
            ),
        )
        spots.save(Spot(id = 1L, sector = "A", lat = spotALat, lng = spotALng, occupied = false))
        spots.save(Spot(id = 2L, sector = "B", lat = spotBLat, lng = spotBLng, occupied = false))
    }

    @Test
    fun `given EXIT em datas e setores distintos when GET revenue then soma apenas o setor e dia consultados via ledger`() {
        // given quatro fluxos completos com EXIT em janelas e setores diferentes
        simulateFullFlow(
            sector = "A",
            entry = "2026-05-09T23:00:00",
            exit = "2026-05-10T00:00:00",
        )
        simulateFullFlow(
            sector = "A",
            entry = "2026-05-10T22:00:00",
            exit = "2026-05-10T23:59:59",
        )
        simulateFullFlow(
            sector = "B",
            entry = "2026-05-10T07:00:00",
            exit = "2026-05-10T12:00:00",
        )
        simulateFullFlow(
            sector = "A",
            entry = "2026-05-10T23:00:00",
            exit = "2026-05-11T00:00:00",
        )

        // when consulta setor A no dia 10
        val body = getRevenueBody(date = "2026-05-10", sector = "A")

        // then soma somente as duas saídas do setor A com earned_at em [10/05 00:00, 11/05 00:00) UTC
        // primeira saída: 1h × 40.50 × 0.900 = 36.45 (multiplier 0.90 porque sector estava em 0% no PARKED)
        // segunda saída: 2h × 40.50 × 0.900 = 72.90
        assertEquals(0, BigDecimal("109.35").compareTo(amountFrom(body)))
        assertEquals("BRL", JsonPath.read<String>(body, "$.currency"))
        assertNotNull(JsonPath.read<String>(body, "$.timestamp"))
        assertEquals(4, ledger.count())
    }

    @Test
    fun `given nenhum EXIT no dia consultado when GET revenue then retorna 0_00 com currency BRL`() {
        // given um fluxo encerrado em outro dia
        simulateFullFlow(
            sector = "A",
            entry = "2026-05-09T11:00:00",
            exit = "2026-05-09T12:00:00",
        )

        // when consulta setor A no dia 10
        val body = getRevenueBody(date = "2026-05-10", sector = "A")

        // then
        assertEquals(0, BigDecimal.ZERO.compareTo(amountFrom(body)))
        assertEquals("BRL", JsonPath.read<String>(body, "$.currency"))
    }

    @Test
    fun `given setor inexistente when GET revenue then responde 404`() {
        // given setor INEXISTENTE nao foi cadastrado

        // when / then
        mockMvc.get("/revenue") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"date":"2026-05-10","sector":"INEXISTENTE"}"""
        }.andExpect {
            status { isNotFound() }
        }
    }

    private fun simulateFullFlow(sector: String, entry: String, exit: String) {
        val plate = "PLT${++plateSeq}"
        val (lat, lng) = spotFor(sector)

        mockMvc.post("/webhook") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"event_type":"ENTRY","license_plate":"$plate","entry_time":"$entry"}"""
        }.andExpect { status { isOk() } }

        mockMvc.post("/webhook") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"event_type":"PARKED","license_plate":"$plate","lat":$lat,"lng":$lng}"""
        }.andExpect { status { isOk() } }

        mockMvc.post("/webhook") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"event_type":"EXIT","license_plate":"$plate","exit_time":"$exit"}"""
        }.andExpect { status { isOk() } }
    }

    private fun spotFor(sector: String): Pair<Double, Double> = when (sector) {
        "A" -> spotALat to spotALng
        "B" -> spotBLat to spotBLng
        else -> error("setor $sector sem vaga registrada no teste")
    }

    private fun getRevenueBody(date: String, sector: String): String =
        mockMvc.get("/revenue") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"date":"$date","sector":"$sector"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

    private fun amountFrom(body: String): BigDecimal =
        JsonPath.read<Number>(body, "$.amount").toString().toBigDecimal()
}
