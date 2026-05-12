package com.estapar.parking.sector

import com.estapar.parking.domain.Sector
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SectorServiceTest {

    private val repo: SectorRepository = mock(SectorRepository::class.java)
    private val service = SectorService(repo)

    @Test
    fun `given setor existente when findByName then retorna o setor`() {
        // given
        val sector = sector("A")
        `when`(repo.findByName("A")).thenReturn(sector)

        // when
        val found = service.findByName("A")

        // then
        assertEquals("A", found?.name)
    }

    @Test
    fun `given setor inexistente when findByName then retorna null`() {
        // given
        `when`(repo.findByName("X")).thenReturn(null)

        // when / then
        assertNull(service.findByName("X"))
    }

    @Test
    fun `given setor existente when lockByName then delega para findByNameForUpdate`() {
        // given
        val sector = sector("A")
        `when`(repo.findByNameForUpdate("A")).thenReturn(sector)

        // when
        val found = service.lockByName("A")

        // then
        assertEquals("A", found?.name)
    }

    @Test
    fun `given pelo menos um setor aberto no horario when isAnyOpenAt then retorna true`() {
        // given
        `when`(repo.findAll()).thenReturn(
            listOf(
                sector("A", open = LocalTime.of(8, 0), close = LocalTime.of(18, 0)),
                sector("B", open = LocalTime.of(0, 0), close = LocalTime.of(23, 59)),
            ),
        )

        // when
        val result = service.isAnyOpenAt(LocalTime.of(3, 0))

        // then
        assertTrue(result)
    }

    @Test
    fun `given todos setores fechados no horario when isAnyOpenAt then retorna false`() {
        // given
        `when`(repo.findAll()).thenReturn(
            listOf(sector("A", open = LocalTime.of(8, 0), close = LocalTime.of(18, 0))),
        )

        // when
        val result = service.isAnyOpenAt(LocalTime.of(3, 0))

        // then
        assertFalse(result)
    }

    private fun sector(name: String, open: LocalTime? = null, close: LocalTime? = null) = Sector(
        name = name,
        basePrice = BigDecimal("10.00"),
        maxCapacity = 10,
        openHour = open,
        closeHour = close,
    )
}
