package com.estapar.parking.config

import com.estapar.parking.garage.GarageClosedException
import com.estapar.parking.garage.GarageFullException
import com.estapar.parking.garage.SectorClosedException
import com.estapar.parking.garage.SessionAlreadyOpenException
import com.estapar.parking.garage.SessionAlreadyParkedException
import com.estapar.parking.garage.SessionNotFoundException
import com.estapar.parking.garage.SessionNotParkedException
import com.estapar.parking.garage.SpotAlreadyOccupiedException
import com.estapar.parking.garage.SpotNotFoundException
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

    @ExceptionHandler(SessionNotFoundException::class)
    fun handleSessionNotFound(ex: SessionNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty()))

    @ExceptionHandler(SessionAlreadyParkedException::class)
    fun handleSessionAlreadyParked(ex: SessionAlreadyParkedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty()))

    @ExceptionHandler(SpotNotFoundException::class)
    fun handleSpotNotFound(ex: SpotNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty()))

    @ExceptionHandler(SpotAlreadyOccupiedException::class)
    fun handleSpotAlreadyOccupied(ex: SpotAlreadyOccupiedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty()))

    @ExceptionHandler(SectorClosedException::class)
    fun handleSectorClosed(ex: SectorClosedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty()))

    @ExceptionHandler(SessionNotParkedException::class)
    fun handleSessionNotParked(ex: SessionNotParkedException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty()))
}

data class ErrorResponse(val message: String)
