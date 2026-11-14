#pragma once

#include "FreeRTOS.h"

inline int xTaskCreate(void (*)(void *), const char *, uint32_t, void *, uint8_t, TaskHandle_t *)
{
  return 0;
}
inline void vTaskDelete(TaskHandle_t) {}
inline TickType_t xTaskGetTickCount() { return 0; }
inline void vTaskDelayUntil(TickType_t *, TickType_t) {}
