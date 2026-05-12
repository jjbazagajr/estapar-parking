package com.estapar.parking.revenue

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

@ResponseStatus(HttpStatus.NOT_FOUND)
class SectorNotFoundException(sector: String) :
    RuntimeException("Setor $sector não existe")
