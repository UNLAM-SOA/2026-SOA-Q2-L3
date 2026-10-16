#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/task.h>

#include "Configuracion.h"
#include "ControlLocal.h"
#include "PruebasControl.h"

using namespace Configuracion;

enum class Estado {
  ESPERA,
  MONITOREANDO_SECADO,
  NOTIFICANDO_ROPA_SECA,
  NOTIFICANDO_LLUVIA
};

enum class Evento {
  BOTON_CICLO,
  ROPA_SECA,
  ROPA_HUMEDA,
  LLUVIA_DETECTADA
};

struct LecturasSensores {
  int adcPrenda1;
  int adcPrenda2;
  int adcLluvia;
  int humedadPrenda1;
  int humedadPrenda2;
  int humedadGeneral;
  int progreso;
};

struct MensajeSensores {
  LecturasSensores lecturas;
  uint32_t pulsaciones;
};

QueueHandle_t colaEventos = nullptr;
// A partir de la creación de tareas, solo tareaFSM accede a estos datos/GPIO.
Estado estadoActual = Estado::ESPERA;
LecturasSensores sensores = {};
CondicionesCiclo condiciones;
AlertaSonora alertaSonora;
bool buzzerEncendido = false;

void tareaSensores(void *parametros);
void tareaFSM(void *parametros);
LecturasSensores leerSensores();
Evento generarEvento();
void ejecutarFSM(Evento evento);
void cambiarEstado(Estado nuevoEstado);
void actualizarActuadores();
void apagarActuadores();
const char *nombreEstado(Estado estado);
void mostrarLecturas();

void setup()
{
  Serial.begin(115200);
  pinMode(PIN_BROCHE_1, INPUT);
  pinMode(PIN_BROCHE_2, INPUT);
  pinMode(PIN_LLUVIA, INPUT);
  pinMode(PIN_BOTON, INPUT_PULLUP);
  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);
  analogReadResolution(12);
  apagarActuadores();

  const int falla = probarControlLocal();
  if (falla != 0) {
    Serial.printf("[ERROR] Autoprueba, PruebasControl.h linea %d. Control detenido.\n", falla);
    return;
  }
  Serial.println("[TEST] Control local: OK");
  Serial.println("[FSM] Estado inicial: ESPERA. Sin WiFi/MQTT.");

  // Buzón de última muestra: no conserva condiciones viejas. El contador
  // acumulativo permite recuperar pulsaciones aunque se sobrescriba su muestra.
  colaEventos = xQueueCreate(1, sizeof(MensajeSensores));
  if (colaEventos == nullptr) {
    Serial.println("[ERROR] No se pudo crear la cola. Actuadores apagados.");
    return;
  }

  TaskHandle_t sensoresHandle = nullptr;
  if (xTaskCreate(tareaSensores, "Sensores", PILA_SENSORES, nullptr,
                  PRIORIDAD_TAREAS, &sensoresHandle) != pdPASS) {
    Serial.println("[ERROR] No se pudo crear tareaSensores.");
    vQueueDelete(colaEventos);
    colaEventos = nullptr;
    return;
  }
  if (xTaskCreate(tareaFSM, "FSM", PILA_FSM, nullptr,
                  PRIORIDAD_TAREAS, nullptr) != pdPASS) {
    // Solo sensores está activa: nunca accede a los actuadores.
    vTaskDelete(sensoresHandle);
    vQueueDelete(colaEventos);
    colaEventos = nullptr;
    apagarActuadores();
    Serial.println("[ERROR] No se pudo crear tareaFSM. Control detenido.");
  }
}

void loop()
{
}

LecturasSensores leerSensores()
{
  LecturasSensores lectura = {};
  lectura.adcPrenda1 = analogRead(PIN_BROCHE_1);
  lectura.adcPrenda2 = analogRead(PIN_BROCHE_2);
  lectura.adcLluvia = analogRead(PIN_LLUVIA);
  lectura.humedadPrenda1 = calcularHumedad(lectura.adcPrenda1, BROCHE_1);
  lectura.humedadPrenda2 = calcularHumedad(lectura.adcPrenda2, BROCHE_2);
  lectura.humedadGeneral = lectura.humedadPrenda1 > lectura.humedadPrenda2
    ? lectura.humedadPrenda1 : lectura.humedadPrenda2;
  lectura.progreso = calcularProgreso(lectura.humedadGeneral);
  return lectura;
}

void tareaSensores(void *parametros)
{
  (void)parametros;
  BotonAntirrebote boton;
  uint32_t pulsaciones = 0;
  TickType_t ultimaLectura = xTaskGetTickCount();
  while (true) {
    MensajeSensores mensaje = {};
    mensaje.lecturas = leerSensores();
    const bool pulsado = boton.actualizar(digitalRead(PIN_BOTON) == LOW, millis());
    if (pulsado) {
      ++pulsaciones;
    }
    mensaje.pulsaciones = pulsaciones;
    if (xQueueOverwrite(colaEventos, &mensaje) != pdPASS) {
      Serial.println("[ERROR] No se pudo entregar la muestra.");
    }
    vTaskDelayUntil(&ultimaLectura, pdMS_TO_TICKS(PERIODO_LECTURA_MS));
  }
}

void tareaFSM(void *parametros)
{
  (void)parametros;
  MensajeSensores mensaje;
  uint32_t pulsacionesProcesadas = 0;
  uint32_t ultimoLogMs = millis();
  while (true) {
    if (xQueueReceive(colaEventos, &mensaje,
                      pdMS_TO_TICKS(PERIODO_LECTURA_MS)) == pdTRUE) {
      sensores = mensaje.lecturas;
      // El contador conserva los eventos de pulsación aunque se sobrescriba
      // su muestra original. Cada pulsación actúa una vez.
      while (pulsacionesProcesadas != mensaje.pulsaciones) {
        ++pulsacionesProcesadas;
        ejecutarFSM(Evento::BOTON_CICLO);
      }
      if (estadoActual != Estado::ESPERA) {
        condiciones.actualizar(sensores.humedadGeneral, sensores.adcLluvia, millis());
        ejecutarFSM(generarEvento());
      }
    }
    // Se atiende el plazo sonoro aun si no llega una nueva lectura.
    actualizarActuadores();
    if (transcurrio(millis(), ultimoLogMs, PERIODO_LOG_MS)) {
      ultimoLogMs = millis();
      mostrarLecturas();
    }
  }
}

Evento generarEvento()
{
  // La finalización ya fue procesada; entre condiciones, la lluvia prevalece.
  if (condiciones.lluvia.valor) {
    return Evento::LLUVIA_DETECTADA;
  }
  return condiciones.seca.valor ? Evento::ROPA_SECA : Evento::ROPA_HUMEDA;
}

// Patrón académico: switch de estado y switch de evento en cada estado.
void ejecutarFSM(Evento evento)
{
  switch (estadoActual) {
    case Estado::ESPERA:
      switch (evento) {
        case Evento::BOTON_CICLO:
          condiciones.reiniciar(millis());
          cambiarEstado(Estado::MONITOREANDO_SECADO);
          break;
        default:
          break;
      }
      break;

    case Estado::MONITOREANDO_SECADO:
      switch (evento) {
        case Evento::BOTON_CICLO:
          cambiarEstado(Estado::ESPERA);
          break;
        case Evento::LLUVIA_DETECTADA:
          cambiarEstado(Estado::NOTIFICANDO_LLUVIA);
          break;
        case Evento::ROPA_SECA:
          cambiarEstado(Estado::NOTIFICANDO_ROPA_SECA);
          break;
        case Evento::ROPA_HUMEDA:
        default:
          break;
      }
      break;

    case Estado::NOTIFICANDO_ROPA_SECA:
      switch (evento) {
        case Evento::BOTON_CICLO:
          cambiarEstado(Estado::ESPERA);
          break;
        case Evento::LLUVIA_DETECTADA:
          cambiarEstado(Estado::NOTIFICANDO_LLUVIA);
          break;
        case Evento::ROPA_HUMEDA:
          cambiarEstado(Estado::MONITOREANDO_SECADO);
          break;
        case Evento::ROPA_SECA:
        default:
          break;
      }
      break;

    case Estado::NOTIFICANDO_LLUVIA:
      switch (evento) {
        case Evento::BOTON_CICLO:
          cambiarEstado(Estado::ESPERA);
          break;
        case Evento::ROPA_SECA:
          cambiarEstado(Estado::NOTIFICANDO_ROPA_SECA);
          break;
        case Evento::ROPA_HUMEDA:
          cambiarEstado(Estado::MONITOREANDO_SECADO);
          break;
        case Evento::LLUVIA_DETECTADA:
        default:
          break;
      }
      break;
  }
}

void cambiarEstado(Estado nuevoEstado)
{
  if (nuevoEstado == estadoActual) {
    return;
  }
  estadoActual = nuevoEstado;
  // Solo una entrada real a una alerta inicia el plazo sonoro.
  if (nuevoEstado == Estado::NOTIFICANDO_ROPA_SECA ||
      nuevoEstado == Estado::NOTIFICANDO_LLUVIA) {
    alertaSonora.iniciar(millis());
  }
  actualizarActuadores();
  Serial.printf("[FSM] %s\n", nombreEstado(estadoActual));
}

void actualizarActuadores()
{
  if (estadoActual == Estado::ESPERA) {
    apagarActuadores();
    return;
  }
  analogWrite(PIN_LED, estadoActual == Estado::NOTIFICANDO_ROPA_SECA
    ? PWM_MAXIMO : calcularPwm(sensores.progreso));
  const bool alerta = estadoActual == Estado::NOTIFICANDO_ROPA_SECA ||
                      estadoActual == Estado::NOTIFICANDO_LLUVIA;
  const uint32_t cadencia = estadoActual == Estado::NOTIFICANDO_LLUVIA
    ? CADENCIA_LLUVIA_MS : CADENCIA_SECO_MS;
  const bool encender = alerta && alertaSonora.actualizar(millis(), cadencia);
  if (encender != buzzerEncendido) {
    if (encender) {
      tone(PIN_BUZZER, FRECUENCIA_BUZZER_HZ);
    } else {
      noTone(PIN_BUZZER);
    }
    buzzerEncendido = encender;
  }
}

void apagarActuadores()
{
  analogWrite(PIN_LED, 0);
  noTone(PIN_BUZZER);
  buzzerEncendido = false;
}

const char *nombreEstado(Estado estado)
{
  switch (estado) {
    case Estado::ESPERA: return "ESPERA";
    case Estado::MONITOREANDO_SECADO: return "MONITOREANDO_SECADO";
    case Estado::NOTIFICANDO_ROPA_SECA: return "NOTIFICANDO_ROPA_SECA";
    case Estado::NOTIFICANDO_LLUVIA: return "NOTIFICANDO_LLUVIA";
  }
  return "DESCONOCIDO";
}

void mostrarLecturas()
{
  Serial.printf("[SENSORES] ADC=%d/%d lluvia=%d H=%d/%d general=%d progreso=%d "
                "seco=%d lluviaConfirmada=%d buzzer=%d estado=%s\n",
                sensores.adcPrenda1, sensores.adcPrenda2, sensores.adcLluvia,
                sensores.humedadPrenda1, sensores.humedadPrenda2,
                sensores.humedadGeneral, sensores.progreso,
                condiciones.seca.valor, condiciones.lluvia.valor,
                buzzerEncendido, nombreEstado(estadoActual));
}
