#pragma once

#include "ProtocoloMqtt.h"

// Operaciones breves sincronizadas entre sensores, FSM y comunicación.
// No ejecutan red, logs ni esperas de FreeRTOS bajo el bloqueo.
void compartirEstado(const InstantaneaSistema &actual);
InstantaneaSistema leerEstado();
void establecerConexionMqtt(bool conectado);
bool entregarComando(Evento evento);
Evento tomarComando();
uint32_t muestrearPulsador();
uint32_t cortarPulsaciones();
