package com.estapar.parking.revenue

import com.estapar.parking.domain.ParkingSession
import com.estapar.parking.domain.ParkingSessionRepository
import com.estapar.parking.domain.Sector
import com.estapar.parking.domain.SectorRepository
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
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

    private var plateSeq = 0

    @Autowired
    private lateinit var sectors: SectorRepository

    @Autowired
    private lateinit var sessions: ParkingSessionRepository

    @BeforeEach
    fun setup() {
        sessions.deleteAll()
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
    }

    @Test
    fun `given sessoes encerradas no dia em setores diferentes when GET revenue then soma apenas o setor e dia consultados`() {
        // given quatro sessoes encerradas em janelas diferentes
        persistClosedSession(
            sector = "A",
            exitAt = Instant.parse("2026-05-10T00:00:00Z"),
            amount = BigDecimal("40.50"),
        )
        persistClosedSession(
            sector = "A",
            exitAt = Instant.parse("2026-05-10T23:59:59Z"),
            amount = BigDecimal("81.00"),
        )
        persistClosedSession(
            sector = "B",
            exitAt = Instant.parse("2026-05-10T12:00:00Z"),
            amount = BigDecimal("10.25"),
        )
        persistClosedSession(
            sector = "A",
            exitAt = Instant.parse("2026-05-11T00:00:00Z"),
            amount = BigDecimal("99.99"),
        )

        // when consulta setor A no dia 10
        val body = getRevenueBody(date = "2026-05-10", sector = "A")

        // then soma somente as duas sessoes do setor A com exit_time em [10/05 00:00, 11/05 00:00)
        assertEquals(0, BigDecimal("121.50").compareTo(amountFrom(body)))
        assertEquals("BRL", JsonPath.read<String>(body, "$.currency"))
        assertNotNull(JsonPath.read<String>(body, "$.timestamp"))
    }

    @Test
    fun `given nenhuma sessao encerrada no dia consultado when GET revenue then retorna 0_00 com currency BRL`() {
        // given uma sessao do setor A em outro dia
        persistClosedSession(
            sector = "A",
            exitAt = Instant.parse("2026-05-09T12:00:00Z"),
            amount = BigDecimal("40.50"),
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

    private fun persistClosedSession(sector: String, exitAt: Instant, amount: BigDecimal) {
        sessions.save(
            ParkingSession(
                licensePlate = "PLT${++plateSeq}",
                entryTime = exitAt.minusSeconds(3600),
                priceMultiplier = BigDecimal("1.000"),
                sector = sector,
                spotId = null,
                parkedTime = exitAt.minusSeconds(3000),
                exitTime = exitAt,
                amountCharged = amount,
            ),
        )
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
