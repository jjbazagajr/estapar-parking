package com.estapar.parking.revenue

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/revenue")
@Tag(name = "Revenue", description = "Consulta de receita realizada por setor e data")
class RevenueController(
    private val service: RevenueService,
) {

    @GetMapping
    @Operation(summary = "Retorna receita acumulada para um setor em uma data")
    fun revenue(
        @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @RequestParam("sector") sector: String,
    ): RevenueResponse = service.revenueFor(date, sector)
}
