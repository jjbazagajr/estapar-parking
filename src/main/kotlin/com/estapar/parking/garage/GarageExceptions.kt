package com.estapar.parking.garage

class SessionAlreadyOpenException(plate: String) :
    RuntimeException("Já existe sessão aberta para placa $plate")

class GarageFullException :
    RuntimeException("Garagem está com lotação máxima")

class GarageClosedException :
    RuntimeException("Garagem fechada no momento")
