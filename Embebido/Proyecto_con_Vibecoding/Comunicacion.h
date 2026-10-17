#pragma once

#include "ProtocoloMqtt.h"

// Solo copian datos bajo sección crítica; nunca esperan a la red.
void compartirEstado(const InstantaneaSistema &actual);
Evento tomarComando(uint32_t ahora);
bool iniciarComunicacion();
