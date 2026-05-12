package com.estapar.parking.domain

sealed class DomainRuleViolation(message: String) : RuntimeException(message)

class SessionAlreadyOpenException(plate: String) :
    DomainRuleViolation("Já existe sessão aberta para placa $plate")

class GarageClosedException :
    DomainRuleViolation("Garagem fechada no momento")

class SectorFullException(sector: String) :
    DomainRuleViolation("Setor $sector está com lotação máxima")

class SessionNotFoundException(plate: String) :
    DomainRuleViolation("Não existe sessão aberta para placa $plate")

class SessionAlreadyParkedException(plate: String) :
    DomainRuleViolation("Sessão da placa $plate já está estacionada")

class SpotNotFoundException(lat: Double, lng: Double) :
    DomainRuleViolation("Não existe vaga nas coordenadas ($lat, $lng)")

class SpotAlreadyOccupiedException(lat: Double, lng: Double) :
    DomainRuleViolation("Vaga em ($lat, $lng) já está ocupada")

class SectorClosedException(sector: String) :
    DomainRuleViolation("Setor $sector fechado no momento")

class SectorMissingException(sector: String) :
    DomainRuleViolation("Setor $sector referenciado pelo spot não existe")

class SessionNotParkedException(plate: String) :
    DomainRuleViolation("Sessão da placa $plate ainda não estacionou")

class SessionAlreadyExitedException(plate: String) :
    DomainRuleViolation("Sessão da placa $plate já foi encerrada")
