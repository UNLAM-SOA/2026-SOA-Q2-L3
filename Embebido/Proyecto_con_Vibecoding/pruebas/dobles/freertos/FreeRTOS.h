#pragma once

#include <stddef.h>
#include <stdint.h>

// Firmas mínimas para compilar el .ino, no implementan un scheduler.
using TickType_t = uint32_t;
using TaskHandle_t = void *;
using QueueHandle_t = void *;
constexpr int pdPASS = 1;
constexpr int pdTRUE = 1;
inline TickType_t pdMS_TO_TICKS(uint32_t ms) { return ms; }
