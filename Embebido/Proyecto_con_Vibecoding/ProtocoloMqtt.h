#pragma once

#include <stddef.h>
#include <string.h>
#include "ControlLocal.h"

enum class Estado {
  ESPERA,
  MONITOREANDO_SECADO,
  NOTIFICANDO_ROPA_SECA,
  NOTIFICANDO_LLUVIA
};

enum class Evento {
  NINGUNO,
  BOTON_CICLO,
  COMANDO_INICIAR,
  COMANDO_FINALIZAR,
  ROPA_SECA,
  ROPA_HUMEDA,
  LLUVIA_DETECTADA
};

struct InstantaneaSistema {
  Estado estado = Estado::ESPERA;
  uint32_t revisionEstado = 0;
  uint32_t ciclo = 0;
  bool lecturaValida = false;
  int humedad = 0;
};

inline const char *nombreEstadoMqtt(Estado estado)
{
  switch (estado) {
    case Estado::ESPERA: return "ESPERA";
    case Estado::MONITOREANDO_SECADO: return "MONITOREANDO_SECADO";
    case Estado::NOTIFICANDO_ROPA_SECA: return "ROPA_SECA";
    case Estado::NOTIFICANDO_LLUVIA: return "LLUVIA";
  }
  return nullptr;
}

inline Evento interpretarComando(const char *topic, const uint8_t *datos, size_t longitud)
{
  if (topic == nullptr || datos == nullptr ||
      strcmp(topic, Configuracion::MQTT_TOPIC_COMANDO) != 0) {
    return Evento::NINGUNO;
  }
  if (longitud == sizeof("INICIAR") - 1 && memcmp(datos, "INICIAR", longitud) == 0) {
    return Evento::COMANDO_INICIAR;
  }
  if (longitud == sizeof("FINALIZAR") - 1 && memcmp(datos, "FINALIZAR", longitud) == 0) {
    return Evento::COMANDO_FINALIZAR;
  }
  return Evento::NINGUNO;
}

// Acceso y captura del reloj se protegen juntos fuera de esta lógica portable;
// el tiempo de consumo no puede haberse capturado antes de adquirir el bloqueo.
struct BuzonComandos {
  bool conectado = false;
  Evento pendiente = Evento::NINGUNO;
  uint32_t recibidoMs = 0;

  void establecerConexion(bool disponible)
  {
    conectado = disponible;
    pendiente = Evento::NINGUNO;
  }

  bool agregar(Evento evento, uint32_t ahora)
  {
    if (!conectado || (evento != Evento::COMANDO_INICIAR &&
                        evento != Evento::COMANDO_FINALIZAR)) {
      return false;
    }
    // Un inicio no puede desplazar una finalización pendiente. Repeticiones
    // tampoco rejuvenecen un comando que la FSM todavía no procesó.
    if (pendiente == Evento::NINGUNO ||
        (evento == Evento::COMANDO_FINALIZAR && pendiente != evento)) {
      pendiente = evento;
      recibidoMs = ahora;
      return true;
    }
    return false;
  }

  Evento tomar(uint32_t ahora)
  {
    const Evento evento = pendiente;
    pendiente = Evento::NINGUNO;
    return conectado && !transcurrio(ahora, recibidoMs, Configuracion::VIGENCIA_COMANDO_MS)
      ? evento : Evento::NINGUNO;
  }
};

struct PublicacionesMqtt {
  bool estadoEnviado = false;
  bool sensorEnviado = false;
  uint32_t ultimoEstadoMs = 0;
  uint32_t ultimoSensorMs = 0;
  uint32_t revisionEnviada = 0;
  uint32_t cicloEnviado = 0;

  bool correspondeEstado(const InstantaneaSistema &actual, uint32_t ahora) const
  {
    return !estadoEnviado || actual.revisionEstado != revisionEnviada ||
      transcurrio(ahora, ultimoEstadoMs, Configuracion::PUBLICACION_ESTADO_MS);
  }

  bool correspondeSensor(const InstantaneaSistema &actual, uint32_t ahora) const
  {
    return actual.estado != Estado::ESPERA && nombreEstadoMqtt(actual.estado) != nullptr &&
      actual.lecturaValida &&
      actual.humedad >= 0 && actual.humedad <= 100 &&
      (!sensorEnviado || actual.ciclo != cicloEnviado ||
       transcurrio(ahora, ultimoSensorMs, Configuracion::PUBLICACION_SENSOR_MS));
  }

  void confirmarEstado(const InstantaneaSistema &actual, uint32_t ahora)
  {
    estadoEnviado = true;
    revisionEnviada = actual.revisionEstado;
    ultimoEstadoMs = ahora;
  }

  void confirmarSensor(const InstantaneaSistema &actual, uint32_t ahora)
  {
    sensorEnviado = true;
    cicloEnviado = actual.ciclo;
    ultimoSensorMs = ahora;
  }
};
