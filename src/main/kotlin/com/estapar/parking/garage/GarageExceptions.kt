package com.estapar.parking.garage

class SessionAlreadyOpenException(plate: String) :
    RuntimeException("Já existe sessão aberta para placa $plate")

class GarageFullException :
    RuntimeException("Garagem está com lotação máxima")

class GarageClosedException :
    RuntimeException("Garagem fechada no momento")

class SessionNotFoundException(plate: String) :
    RuntimeException("Não existe sessão aberta para placa $plate")

class SessionAlreadyParkedException(plate: String) :
    RuntimeException("Sessão da placa $plate já está estacionada")

class SpotNotFoundException(lat: Double, lng: Double) :
    RuntimeException("Não existe vaga nas coordenadas ($lat, $lng)")

class SpotAlreadyOccupiedException(lat: Double, lng: Double) :
    RuntimeException("Vaga em ($lat, $lng) já está ocupada")

class SectorClosedException(sector: String) :
    RuntimeException("Setor $sector fechado no momento")

class SectorMissingException(sector: String) :
    IllegalStateException("Setor $sector referenciado pelo spot não existe")

class SessionNotParkedException(plate: String) :
    RuntimeException("Sessão da placa $plate ainda não estacionou")
