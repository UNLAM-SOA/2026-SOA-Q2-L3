#pragma once

#include <stddef.h>
#include <stdint.h>
#include <assert.h>

// Firmas mínimas para compilar el .ino, no implementan un scheduler.
using TickType_t = uint32_t;
using TaskHandle_t = void *;
using QueueHandle_t = void *;
constexpr int pdPASS = 1;
constexpr int pdTRUE = 1;
inline TickType_t pdMS_TO_TICKS(uint32_t ms) { return ms; }

struct portMUX_TYPE {};
#define portMUX_INITIALIZER_UNLOCKED {}
extern void (*antesDeBloquear)();
extern unsigned bloqueosPrueba;

// Inyecta otra operación justo antes de adquirir el bloqueo, no mientras está
// tomado. No representa concurrencia de CPU ni reemplaza FreeRTOS.
inline void portENTER_CRITICAL(portMUX_TYPE *)
{
  assert(bloqueosPrueba == 0);
  if (antesDeBloquear != nullptr) {
    auto intercalar = antesDeBloquear;
    antesDeBloquear = nullptr;
    intercalar();
  }
  ++bloqueosPrueba;
}
inline void portEXIT_CRITICAL(portMUX_TYPE *)
{
  assert(bloqueosPrueba == 1);
  --bloqueosPrueba;
}
