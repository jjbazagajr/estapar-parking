package com.estapar.parking.config

import com.estapar.parking.garage.GarageClosedException
import com.estapar.parking.garage.GarageFullException
import com.estapar.parking.garage.SessionAlreadyOpenException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

@ControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(SessionAlreadyOpenException::class)
    fun handleSessionAlreadyOpen(ex: SessionAlreadyOpenException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty()))

    @ExceptionHandler(GarageFullException::class)
    fun handleGarageFull(ex: GarageFullException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty()))

    @ExceptionHandler(GarageClosedException::class)
    fun handleGarageClosed(ex: GarageClosedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty()))
}

data class ErrorResponse(val message: String)
