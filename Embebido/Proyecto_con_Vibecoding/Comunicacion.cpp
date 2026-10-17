#include <Arduino.h>
#include <WiFi.h>
#include <PubSubClient.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

#include "Comunicacion.h"
#if __has_include("ConfiguracionWifi.h")
#include "ConfiguracionWifi.h"
#else
#include "ConfiguracionWifi.ejemplo.h"
#endif

namespace {
using namespace Configuracion;

portMUX_TYPE mutexIntercambio = portMUX_INITIALIZER_UNLOCKED;
InstantaneaSistema ultimaInstantanea;
BuzonComandos comandos;

InstantaneaSistema leerEstado()
{
  portENTER_CRITICAL(&mutexIntercambio);
  const InstantaneaSistema actual = ultimaInstantanea;
  portEXIT_CRITICAL(&mutexIntercambio);
  return actual;
}

void establecerConexion(bool conectado)
{
  portENTER_CRITICAL(&mutexIntercambio);
  comandos.establecerConexion(conectado);
  portEXIT_CRITICAL(&mutexIntercambio);
}

void recibirMensaje(char *topic, uint8_t *payload, unsigned int longitud)
{
  const Evento evento = interpretarComando(topic, payload, longitud);
  if (evento == Evento::NINGUNO) {
    Serial.println("[MQTT] Mensaje rechazado: topic, contenido o longitud invalidos.");
    return;
  }
  const uint32_t ahora = millis();
  portENTER_CRITICAL(&mutexIntercambio);
  const bool aceptado = comandos.agregar(evento, ahora);
  portEXIT_CRITICAL(&mutexIntercambio);
  Serial.printf("[MQTT] %s: %s (no implica ejecucion).\n",
    evento == Evento::COMANDO_INICIAR ? "INICIAR" : "FINALIZAR",
    aceptado ? "entregado a FSM" : "descartado/repetido");
}

bool publicarActual(PubSubClient &mqtt, PublicacionesMqtt &publicaciones)
{
  InstantaneaSistema actual = leerEstado();
  if (publicaciones.correspondeEstado(actual, millis())) {
    const char *estado = nombreEstadoMqtt(actual.estado);
    if (estado == nullptr || !mqtt.publish(MQTT_TOPIC_ESTADO, estado, false)) {
      return false;
    }
    publicaciones.confirmarEstado(actual, millis());
    Serial.printf("[MQTT] Estado publicado: %s\n", estado);
  }
  // Publicar puede esperar al socket: volver a obtener la situación actual.
  actual = leerEstado();
  if (actual.revisionEstado != publicaciones.revisionEnviada) {
    return true; // Primero informar el nuevo estado en la próxima iteración.
  }
  if (publicaciones.correspondeSensor(actual, millis())) {
    char humedad[4];
    snprintf(humedad, sizeof(humedad), "%d", actual.humedad);
    if (!mqtt.publish(MQTT_TOPIC_SENSOR, humedad, false)) {
      return false;
    }
    publicaciones.confirmarSensor(actual, millis());
    Serial.printf("[MQTT] Humedad publicada: %s\n", humedad);
  }
  return true;
}

// WiFi, socket y PubSubClient pertenecen exclusivamente a esta tarea.
void tareaComunicacion(void *parametros)
{
  (void)parametros;
  WiFiClient tcp;
  PubSubClient mqtt(tcp);
  mqtt.setServer(MQTT_BROKER, MQTT_PUERTO);
  mqtt.setCallback(recibirMensaje);
  mqtt.setSocketTimeout(TIMEOUT_MQTT_S);
  mqtt.setKeepAlive(KEEPALIVE_MQTT_S);
  if (!mqtt.setBufferSize(BUFFER_MQTT_BYTES)) {
    Serial.println("[ERROR] Sin buffer MQTT. Continua solo el control local.");
    // Salir del ámbito destruye los clientes antes de eliminar esta tarea.
    return;
  }

  char clienteId[40];
  const uint64_t mac = ESP.getEfuseMac();
  snprintf(clienteId, sizeof(clienteId), "lavadero-ia-%04X%08X",
    unsigned((mac >> 32) & 0xFFFF), unsigned(mac & 0xFFFFFFFF));
  WiFi.persistent(false);
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(false);
  WiFi.begin(Credenciales::SSID, Credenciales::PASSWORD);
  Serial.printf("[WIFI] Iniciando. Cliente MQTT: %s\n", clienteId);

  uint32_t ultimoWiFiMs = millis();
  uint32_t ultimoMqttMs = millis() - REINTENTO_CONEXION_MS;
  bool wifiAnterior = false;
  bool sesionActiva = false;
  PublicacionesMqtt publicaciones;
  while (true) {
    const bool wifiDisponible = WiFi.status() == WL_CONNECTED;
    if (!wifiDisponible) {
      if (sesionActiva) {
        establecerConexion(false);
        sesionActiva = false;
        tcp.stop();
        Serial.println("[MQTT] Sin WiFi. Descartados comandos pendientes.");
      }
      if (transcurrio(millis(), ultimoWiFiMs, REINTENTO_CONEXION_MS)) {
        WiFi.begin(Credenciales::SSID, Credenciales::PASSWORD);
        ultimoWiFiMs = millis();
        Serial.println("[WIFI] Reintentando conexion.");
      }
    } else {
      if (!wifiAnterior) {
        Serial.println("[WIFI] Conectado.");
        ultimoMqttMs = millis() - REINTENTO_CONEXION_MS;
      }
      if (!mqtt.connected()) {
        if (sesionActiva) {
          establecerConexion(false);
          sesionActiva = false;
          ultimoMqttMs = millis();
          Serial.println("[MQTT] Desconectado. Descartados comandos pendientes.");
        }
        if (transcurrio(millis(), ultimoMqttMs, REINTENTO_CONEXION_MS)) {
          Serial.println("[MQTT] Intentando conectar...");
          tcp.stop();
          // Preconectar con el timeout explícito, compatible con core 2.x/3.x.
          // DNS y escrituras pueden esperar aparte; nunca se ejecutan en FSM.
          const bool conectado = tcp.connect(MQTT_BROKER, MQTT_PUERTO, TIMEOUT_TCP_MS) &&
                                  mqtt.connect(clienteId) &&
                                  mqtt.subscribe(MQTT_TOPIC_COMANDO, 0);
          ultimoMqttMs = millis();
          if (conectado) {
            publicaciones = PublicacionesMqtt{};
            establecerConexion(true);
            sesionActiva = true;
            Serial.println("[MQTT] Conectado; suscripcion enviada (QoS 0, sesion limpia).");
          } else {
            establecerConexion(false);
            tcp.stop();
            Serial.printf("[MQTT] Fallo de conexion/suscripcion: %d\n", mqtt.state());
          }
        }
      }
      if (sesionActiva && (!mqtt.loop() || WiFi.status() != WL_CONNECTED ||
                           !publicarActual(mqtt, publicaciones))) {
        establecerConexion(false);
        sesionActiva = false;
        tcp.stop();
        ultimoMqttMs = millis();
        Serial.println("[MQTT] Error de intercambio. Se reintentara sin historial.");
      }
    }
    wifiAnterior = wifiDisponible;
    vTaskDelay(pdMS_TO_TICKS(PERIODO_COMUNICACION_MS));
  }
}

// Una tarea FreeRTOS no puede retornar; el cuerpo separado permite RAII.
void ejecutarComunicacion(void *parametros)
{
  tareaComunicacion(parametros);
  vTaskDelete(nullptr);
}
} // namespace

void compartirEstado(const InstantaneaSistema &actual)
{
  portENTER_CRITICAL(&mutexIntercambio);
  ultimaInstantanea = actual;
  portEXIT_CRITICAL(&mutexIntercambio);
}

Evento tomarComando(uint32_t ahora)
{
  portENTER_CRITICAL(&mutexIntercambio);
  const Evento evento = comandos.tomar(ahora);
  portEXIT_CRITICAL(&mutexIntercambio);
  return evento;
}

bool iniciarComunicacion()
{
  return xTaskCreate(ejecutarComunicacion, "Comunicacion", Configuracion::PILA_COMUNICACION,
    nullptr, Configuracion::PRIORIDAD_COMUNICACION, nullptr) == pdPASS;
}
