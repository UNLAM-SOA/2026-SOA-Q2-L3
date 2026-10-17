#pragma once

#include "ProtocoloMqtt.h"

inline int probarMqtt()
{
#define COMPROBAR(condicion) do { if (!(condicion)) return __LINE__; } while (false)
  using namespace Configuracion;
  const uint8_t iniciar[] = "INICIAR";
  const uint8_t finalizar[] = "FINALIZAR";
  COMPROBAR(interpretarComando(MQTT_TOPIC_COMANDO, iniciar, 7) == Evento::COMANDO_INICIAR);
  COMPROBAR(interpretarComando(MQTT_TOPIC_COMANDO, finalizar, 9) == Evento::COMANDO_FINALIZAR);
  COMPROBAR(interpretarComando(MQTT_TOPIC_ESTADO, iniciar, 7) == Evento::NINGUNO);
  COMPROBAR(interpretarComando(nullptr, iniciar, 7) == Evento::NINGUNO);
  COMPROBAR(interpretarComando(MQTT_TOPIC_COMANDO, nullptr, 7) == Evento::NINGUNO);
  COMPROBAR(interpretarComando(MQTT_TOPIC_COMANDO, iniciar, 0) == Evento::NINGUNO);
  COMPROBAR(interpretarComando(MQTT_TOPIC_COMANDO, iniciar, 6) == Evento::NINGUNO);
  COMPROBAR(interpretarComando(MQTT_TOPIC_COMANDO, iniciar, 8) == Evento::NINGUNO);
  COMPROBAR(interpretarComando(MQTT_TOPIC_COMANDO, iniciar, 1000) == Evento::NINGUNO);
  const uint8_t minusculas[] = "iniciar";
  const uint8_t espacio[] = "INICIAR ";
  const uint8_t binario[] = {'I', 'N', 'I', 0, 'I', 'A', 'R'};
  COMPROBAR(interpretarComando(MQTT_TOPIC_COMANDO, minusculas, 7) == Evento::NINGUNO);
  COMPROBAR(interpretarComando(MQTT_TOPIC_COMANDO, espacio, 8) == Evento::NINGUNO);
  COMPROBAR(interpretarComando(MQTT_TOPIC_COMANDO, binario, 7) == Evento::NINGUNO);
  COMPROBAR(strcmp(nombreEstadoMqtt(Estado::NOTIFICANDO_ROPA_SECA), "ROPA_SECA") == 0);
  COMPROBAR(strcmp(nombreEstadoMqtt(Estado::NOTIFICANDO_LLUVIA), "LLUVIA") == 0);
  COMPROBAR(nombreEstadoMqtt(static_cast<Estado>(99)) == nullptr);

  BuzonComandos buzon;
  COMPROBAR(!buzon.agregar(Evento::COMANDO_INICIAR, 0));
  buzon.establecerConexion(true);
  COMPROBAR(!buzon.agregar(Evento::BOTON_CICLO, 0));
  COMPROBAR(buzon.agregar(Evento::COMANDO_INICIAR, 0));
  COMPROBAR(buzon.tomar(1) == Evento::COMANDO_INICIAR);
  COMPROBAR(buzon.tomar(2) == Evento::NINGUNO);
  COMPROBAR(buzon.agregar(Evento::COMANDO_INICIAR, 10));
  COMPROBAR(!buzon.agregar(Evento::COMANDO_INICIAR, 999));
  COMPROBAR(buzon.tomar(1010) == Evento::NINGUNO);
  COMPROBAR(buzon.agregar(Evento::COMANDO_INICIAR, 2000));
  COMPROBAR(buzon.agregar(Evento::COMANDO_FINALIZAR, 2001));
  COMPROBAR(!buzon.agregar(Evento::COMANDO_INICIAR, 2002));
  COMPROBAR(buzon.tomar(2003) == Evento::COMANDO_FINALIZAR);
  COMPROBAR(buzon.agregar(Evento::COMANDO_INICIAR, 3000));
  buzon.establecerConexion(false);
  buzon.establecerConexion(true);
  COMPROBAR(buzon.tomar(3001) == Evento::NINGUNO);
  COMPROBAR(buzon.agregar(Evento::COMANDO_FINALIZAR, UINT32_MAX - 20));
  COMPROBAR(buzon.tomar(10) == Evento::COMANDO_FINALIZAR);

  InstantaneaSistema actual;
  PublicacionesMqtt publicaciones;
  COMPROBAR(publicaciones.correspondeEstado(actual, 0));
  COMPROBAR(!publicaciones.correspondeSensor(actual, 0));
  publicaciones.confirmarEstado(actual, 0);
  COMPROBAR(!publicaciones.correspondeEstado(actual, 4999));
  COMPROBAR(publicaciones.correspondeEstado(actual, 5000));
  ++actual.revisionEstado;
  actual.estado = Estado::MONITOREANDO_SECADO;
  ++actual.ciclo;
  COMPROBAR(publicaciones.correspondeEstado(actual, 10));
  COMPROBAR(!publicaciones.correspondeSensor(actual, 10));
  actual.lecturaValida = true;
  actual.humedad = 60;
  COMPROBAR(publicaciones.correspondeSensor(actual, 10));
  publicaciones.confirmarSensor(actual, 10);
  COMPROBAR(!publicaciones.correspondeSensor(actual, 5009));
  COMPROBAR(publicaciones.correspondeSensor(actual, 5010));
  actual.estado = Estado::ESPERA;
  COMPROBAR(!publicaciones.correspondeSensor(actual, 5010));
  actual.estado = Estado::MONITOREANDO_SECADO;
  ++actual.ciclo;
  actual.lecturaValida = false;
  COMPROBAR(!publicaciones.correspondeSensor(actual, 20));
  actual.lecturaValida = true;
  COMPROBAR(publicaciones.correspondeSensor(actual, 20));
  actual.humedad = -1;
  COMPROBAR(!publicaciones.correspondeSensor(actual, 20));
  actual.humedad = 101;
  COMPROBAR(!publicaciones.correspondeSensor(actual, 20));
  actual.humedad = 100;
  actual.estado = static_cast<Estado>(99);
  COMPROBAR(!publicaciones.correspondeSensor(actual, 20));
  actual.estado = Estado::MONITOREANDO_SECADO;
  actual.humedad = 0;
  COMPROBAR(publicaciones.correspondeSensor(actual, 20));
  publicaciones = PublicacionesMqtt{}; // Reconexión: enviar la situación vigente.
  COMPROBAR(publicaciones.correspondeEstado(actual, 21));
  COMPROBAR(publicaciones.correspondeSensor(actual, 21));
  publicaciones.confirmarEstado(actual, UINT32_MAX - 100);
  COMPROBAR(!publicaciones.correspondeEstado(actual, 4898));
  COMPROBAR(publicaciones.correspondeEstado(actual, 4899));
#undef COMPROBAR
  return 0;
}
