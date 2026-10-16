#pragma once

#include <stdint.h>

namespace Configuracion {
constexpr uint8_t PIN_BROCHE_1 = 35;
constexpr uint8_t PIN_BROCHE_2 = 34;
constexpr uint8_t PIN_LLUVIA = 32;
constexpr uint8_t PIN_BOTON = 26;
constexpr uint8_t PIN_LED = 18;
constexpr uint8_t PIN_BUZZER = 25;

constexpr int ADC_MAXIMO = 4095;
// Provisorios: ADC de prenda completamente húmeda / seca, por broche.
struct CalibracionBroche {
  int humedo;
  int seco;
};
constexpr CalibracionBroche BROCHE_1 = {0, ADC_MAXIMO};
constexpr CalibracionBroche BROCHE_2 = {0, ADC_MAXIMO};
constexpr int UMBRAL_SECO_PCT = 20;
constexpr int UMBRAL_HUMEDO_PCT = 25;
constexpr int UMBRAL_LLUVIA_ADC = 2000;
constexpr bool LLUVIA_CON_ADC_ALTO = true;

constexpr uint32_t PERIODO_LECTURA_MS = 50;
constexpr uint32_t ANTIRREBOTE_MS = 40;
constexpr uint32_t ESTABILIDAD_MS = 1000;
constexpr uint32_t PERIODO_LOG_MS = 1000;
constexpr uint32_t CADENCIA_SECO_MS = 1200;
constexpr uint32_t CADENCIA_LLUVIA_MS = 300;
constexpr uint32_t MAXIMO_ALERTA_MS = 30000;
// Wokwi simula un buzzer pasivo. El módulo físico requiere revisión previa.
constexpr unsigned int FRECUENCIA_BUZZER_HZ = 1000;
constexpr int PWM_MAXIMO = 255;

constexpr uint32_t PILA_SENSORES = 3072;
constexpr uint32_t PILA_FSM = 4096;
constexpr uint8_t PRIORIDAD_TAREAS = 1;

constexpr bool calibracionValida(CalibracionBroche c)
{
  return c.humedo >= 0 && c.humedo <= ADC_MAXIMO &&
         c.seco >= 0 && c.seco <= ADC_MAXIMO && c.humedo != c.seco;
}
static_assert(calibracionValida(BROCHE_1) && calibracionValida(BROCHE_2),
              "Revisar extremos ADC de los broches");
static_assert(UMBRAL_SECO_PCT >= 0 && UMBRAL_SECO_PCT < UMBRAL_HUMEDO_PCT &&
              UMBRAL_HUMEDO_PCT <= 100, "Revisar histeresis");
static_assert(UMBRAL_LLUVIA_ADC >= 0 && UMBRAL_LLUVIA_ADC <= ADC_MAXIMO,
              "Revisar umbral de lluvia");
static_assert(CADENCIA_SECO_MS > 0 && CADENCIA_LLUVIA_MS > 0,
              "Las cadencias deben ser positivas");
} // namespace Configuracion
