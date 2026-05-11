package com.estapar.parking.garage

sealed class WebhookEventIgnored(message: String) : RuntimeException(message)

class SessionAlreadyOpenException(plate: String) :
    WebhookEventIgnored("Já existe sessão aberta para placa $plate")

class GarageFullException :
    WebhookEventIgnored("Garagem está com lotação máxima")

class GarageClosedException :
    WebhookEventIgnored("Garagem fechada no momento")

class SessionNotFoundException(plate: String) :
    WebhookEventIgnored("Não existe sessão aberta para placa $plate")

class SessionAlreadyParkedException(plate: String) :
    WebhookEventIgnored("Sessão da placa $plate já está estacionada")

class SpotNotFoundException(lat: Double, lng: Double) :
    WebhookEventIgnored("Não existe vaga nas coordenadas ($lat, $lng)")

class SpotAlreadyOccupiedException(lat: Double, lng: Double) :
    WebhookEventIgnored("Vaga em ($lat, $lng) já está ocupada")

class SectorClosedException(sector: String) :
    WebhookEventIgnored("Setor $sector fechado no momento")

class SectorMissingException(sector: String) :
    WebhookEventIgnored("Setor $sector referenciado pelo spot não existe")

class SessionNotParkedException(plate: String) :
    WebhookEventIgnored("Sessão da placa $plate ainda não estacionou")

class SessionAlreadyExitedException(plate: String) :
    WebhookEventIgnored("Sessão da placa $plate já foi encerrada")
