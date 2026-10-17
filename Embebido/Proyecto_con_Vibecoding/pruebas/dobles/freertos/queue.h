#pragma once

#include "FreeRTOS.h"

inline QueueHandle_t xQueueCreate(int, size_t) { return nullptr; }
inline void vQueueDelete(QueueHandle_t) {}
inline int xQueueOverwrite(QueueHandle_t, const void *) { return 0; }
inline int xQueueReceive(QueueHandle_t, void *, TickType_t) { return 0; }
