package com.estapar.parking.spot

import com.estapar.parking.domain.Spot
import com.estapar.parking.domain.SpotAlreadyOccupiedException
import com.estapar.parking.domain.SpotNotFoundException
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotServiceTest {

    private val repo: SpotRepository = mock(SpotRepository::class.java)
    private val service = SpotService(repo)

    private val lat = -23.5
    private val lng = -46.6

    @Test
    fun `given vaga existente when findByCoordinates then retorna a vaga`() {
        // given
        val spot = freeSpot()
        `when`(repo.findFirstByLatAndLng(lat, lng)).thenReturn(spot)

        // when
        val found = service.findByCoordinates(lat, lng)

        // then
        assertEquals(spot.id, found.id)
    }

    @Test
    fun `given coordenadas sem vaga when findByCoordinates then lanca SpotNotFoundException`() {
        // given
        `when`(repo.findFirstByLatAndLng(lat, lng)).thenReturn(null)

        // when / then
        assertFailsWith<SpotNotFoundException> { service.findByCoordinates(lat, lng) }
    }

    @Test
    fun `given spot livre when occupy then marca como ocupado e faz saveAndFlush`() {
        // given
        val spot = freeSpot()

        // when
        service.occupy(spot)

        // then
        assertTrue(spot.occupied)
        verify(repo).saveAndFlush(spot)
    }

    @Test
    fun `given spot ja ocupado when occupy then lanca SpotAlreadyOccupiedException antes de salvar`() {
        // given
        val spot = freeSpot().apply { occupy() }

        // when / then
        assertFailsWith<SpotAlreadyOccupiedException> { service.occupy(spot) }
    }

    @Test
    fun `given race no UPDATE when occupy then traduz ObjectOptimisticLockingFailureException em SpotAlreadyOccupiedException`() {
        // given
        val spot = freeSpot()
        `when`(repo.saveAndFlush(spot))
            .thenThrow(ObjectOptimisticLockingFailureException(Spot::class.java, 0L))

        // when / then
        assertFailsWith<SpotAlreadyOccupiedException> { service.occupy(spot) }
    }

    @Test
    fun `given spot ocupado when release then marca como livre`() {
        // given
        val spot = freeSpot().apply { occupy() }

        // when
        service.release(spot)

        // then
        assertFalse(spot.occupied)
    }

    @Test
    fun `given setor when countOccupiedIn then delega ao repo`() {
        // given
        `when`(repo.countBySectorAndOccupiedTrue("A")).thenReturn(3L)

        // when
        val count = service.countOccupiedIn("A")

        // then
        assertEquals(3L, count)
    }

    @Test
    fun `given id inexistente when findById then retorna null`() {
        // given
        `when`(repo.findById(99L)).thenReturn(Optional.empty())

        // when
        val found = service.findById(99L)

        // then
        assertNull(found)
    }

    private fun freeSpot() = Spot(id = 7L, sector = "A", lat = lat, lng = lng, occupied = false)
}
