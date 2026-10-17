#include <Arduino.h>
#include <freertos/FreeRTOS.h>

#include "Intercambio.h"

namespace {
portMUX_TYPE mutexIntercambio = portMUX_INITIALIZER_UNLOCKED;
InstantaneaSistema ultimaInstantanea;
BuzonComandos comandos;
BotonAntirrebote botonCompartido;
uint32_t pulsacionesAceptadas = 0;
} // namespace

void compartirEstado(const InstantaneaSistema &actual)
{
  portENTER_CRITICAL(&mutexIntercambio);
  ultimaInstantanea = actual;
  portEXIT_CRITICAL(&mutexIntercambio);
}

InstantaneaSistema leerEstado()
{
  portENTER_CRITICAL(&mutexIntercambio);
  const InstantaneaSistema actual = ultimaInstantanea;
  portEXIT_CRITICAL(&mutexIntercambio);
  return actual;
}

void establecerConexionMqtt(bool conectado)
{
  portENTER_CRITICAL(&mutexIntercambio);
  comandos.establecerConexion(conectado);
  portEXIT_CRITICAL(&mutexIntercambio);
}

bool entregarComando(Evento evento)
{
  portENTER_CRITICAL(&mutexIntercambio);
  const bool aceptado = comandos.agregar(evento, millis());
  portEXIT_CRITICAL(&mutexIntercambio);
  return aceptado;
}

Evento tomarComando()
{
  portENTER_CRITICAL(&mutexIntercambio);
  // El reloj debe observarse después de adquirir el mismo bloqueo que protege
  // la recepción: nunca puede ser anterior al comando que se está consumiendo.
  const Evento evento = comandos.tomar(millis());
  portEXIT_CRITICAL(&mutexIntercambio);
  return evento;
}

uint32_t muestrearPulsador()
{
  portENTER_CRITICAL(&mutexIntercambio);
  // Aceptación del flanco y contador forman una sola operación frente al corte
  // de finalización, aun si se publica la muestra mucho después.
  if (botonCompartido.actualizar(digitalRead(Configuracion::PIN_BOTON) == LOW, millis())) {
    ++pulsacionesAceptadas;
  }
  const uint32_t cantidad = pulsacionesAceptadas;
  portEXIT_CRITICAL(&mutexIntercambio);
  return cantidad;
}

uint32_t cortarPulsaciones()
{
  portENTER_CRITICAL(&mutexIntercambio);
  const uint32_t corte = pulsacionesAceptadas;
  portEXIT_CRITICAL(&mutexIntercambio);
  return corte;
}
