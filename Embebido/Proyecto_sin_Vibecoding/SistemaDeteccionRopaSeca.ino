#include <Arduino.h>
#include <WiFi.h>
#include <PubSubClient.h>

// ======================================================
// PINES
// ======================================================

constexpr uint8_t PIN_BROCHE_1 = 35;
constexpr uint8_t PIN_BROCHE_2 = 34;
constexpr uint8_t PIN_LLUVIA = 32;
constexpr uint8_t PIN_BOTON_CICLO = 26;
constexpr uint8_t PIN_LED = 18;
constexpr uint8_t PIN_BUZZER = 25;

// ======================================================
// ADC
// ======================================================

constexpr int ADC_MINIMO = 0;
constexpr int ADC_MAXIMO = 4095;

// ======================================================
// HUMEDAD
// ======================================================

constexpr int PORCENTAJE_MINIMO = 0;
constexpr int PORCENTAJE_MAXIMO = 100;
constexpr int UMBRAL_SECO_PCT = 20;

// ======================================================
// LLUVIA
// ======================================================

constexpr int UMBRAL_LLUVIA_ADC = 2000;

// ======================================================
// PWM
// ======================================================

constexpr int PWM_MINIMO = 0;
constexpr int PWM_MAXIMO = 255;

// ======================================================
// TIEMPOS
// ======================================================

constexpr unsigned long PERIODO_LECTURA_MS = 50;
constexpr unsigned long PERIODO_LOG_MS = 1000;
constexpr unsigned long ANTIRREBOTE_BOTON_MS = 40;
constexpr unsigned long PERIODO_BUZZER_SECA_MS = 1200;
constexpr unsigned long PERIODO_BUZZER_LLUVIA_MS = 300;
constexpr unsigned long PERIODO_RECONEXION_MQTT_MS = 5000;
constexpr unsigned long PERIODO_PUBLICACION_SENSOR_MS = 2000;
constexpr unsigned long PERIODO_PUBLICACION_CONEXION_MS = 5000;

// ======================================================
// FRECUENCIAS BUZZER
// ======================================================

constexpr unsigned int FRECUENCIA_BUZZER_SECA_HZ = 900;
constexpr unsigned int FRECUENCIA_BUZZER_LLUVIA_HZ = 1600;

// ======================================================
// WIFI
// ======================================================

constexpr char WIFI_SSID[] = "TU_WIFI";
constexpr char WIFI_PASSWORD[] = "TU_PASSWORD";

// ======================================================
// MQTT
// ======================================================

constexpr char MQTT_BROKER[] = "test.mosquitto.org";
constexpr uint16_t MQTT_PUERTO = 1883;

constexpr char MQTT_CLIENT_ID[] = "esp32-lavadero-grupo3";

constexpr char MQTT_TOPIC_COMANDO[] = "unlam/soa/grupo3/lavadero/comando";
constexpr char MQTT_TOPIC_ESTADO[] = "unlam/soa/grupo3/lavadero/estado";
constexpr char MQTT_TOPIC_SENSOR[] = "unlam/soa/grupo3/lavadero/sensor";
constexpr char MQTT_TOPIC_CONEXION[] = "unlam/soa/grupo3/lavadero/conexion";

constexpr char MQTT_COMANDO_INICIAR[] = "INICIAR";
constexpr char MQTT_COMANDO_FINALIZAR[] = "FINALIZAR";
constexpr char MQTT_MENSAJE_ONLINE[] = "ONLINE";

// ======================================================
// FREERTOS
// ======================================================

constexpr uint16_t TAMANIO_PILA_SENSORES = 2048;
constexpr uint16_t TAMANIO_PILA_FSM = 6144;
constexpr UBaseType_t PRIORIDAD_TAREA = 1;
constexpr uint8_t CANTIDAD_EVENTOS_COLA = 10;

// ======================================================
// ESTADOS
// ======================================================

enum class Estado {
  ESPERA,
  MONITOREANDO_SECADO,
  NOTIFICANDO_ROPA_SECA,
  NOTIFICANDO_LLUVIA
};

// ======================================================
// EVENTOS
// ======================================================

enum class Evento {
  BOTON_CICLO,
  COMANDO_INICIAR,
  COMANDO_FINALIZAR,
  ROPA_SECA,
  ROPA_HUMEDA,
  LLUVIA_DETECTADA
};

// ======================================================
// SENSORES
// ======================================================

struct LecturasSensores {
  int adcPrenda1;
  int adcPrenda2;
  int adcLluvia;

  int humedadPrenda1Pct;
  int humedadPrenda2Pct;
  int humedadGeneralPct;
  int progresoSecadoPct;

  bool lluvia;
  bool botonCicloPulsado;
};

LecturasSensores sensores;

// ======================================================
// ESTADO
// ======================================================

Estado estadoActual = Estado::ESPERA;

// ======================================================
// FREERTOS
// ======================================================

QueueHandle_t colaEventos;

// ======================================================
// WIFI / MQTT
// ======================================================

WiFiClient wifiClient;
PubSubClient mqttClient(wifiClient);

unsigned long ultimoIntentoMQTTMs = 0;
unsigned long ultimaPublicacionSensorMs = 0;
unsigned long ultimaPublicacionConexionMs = 0;

// ======================================================
// TEMPORIZADORES
// ======================================================

unsigned long ultimoLogMs = 0;
unsigned long ultimoCambioBuzzerMs = 0;

// ======================================================
// BOTON
// ======================================================

bool ultimaLecturaBoton = HIGH;
bool estadoEstableBoton = HIGH;
unsigned long ultimoCambioBotonMs = 0;

// ======================================================
// BUZZER
// ======================================================

bool buzzerEncendido = false;

// ======================================================
// DECLARACIONES
// ======================================================

void tareaSensores(void *parametros);
void tareaFSM(void *parametros);

void iniciarWiFi();
void actualizarMQTT();
void recibirMensajeMQTT(char *topic, byte *payload, unsigned int length);
void publicarEstadoMQTT();
void actualizarPublicacionSensorMQTT();
void publicarSensorMQTT();
void actualizarPublicacionConexionMQTT();
void publicarConexionMQTT();

void leerSensores();
void leerBoton();

int calcularHumedad(int lecturaADC);
int calcularProgresoSecado(int humedadGeneral);

bool todasLasPrendasSecas();

Evento generarEvento();

void ejecutarFSM(Evento evento);
void cambiarEstado(Estado nuevoEstado);

void actualizarLedSecado();
void encenderLedSeco();
void apagarLed();

void iniciarBuzzer(unsigned int frecuencia);
void actualizarBuzzer(unsigned long periodo, unsigned int frecuencia);
void encenderBuzzer(unsigned int frecuencia);
void apagarBuzzer();
void apagarActuadores();

void notificarRopaSeca();
void notificarLluvia();

void mostrarLecturas();

const char *nombreEstado(Estado estado);

// ======================================================
// SETUP
// ======================================================

void setup()
{
  Serial.begin(115200);

  pinMode(PIN_BROCHE_1, INPUT);
  pinMode(PIN_BROCHE_2, INPUT);
  pinMode(PIN_LLUVIA, INPUT);
  pinMode(PIN_BOTON_CICLO, INPUT_PULLUP);
  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);

  apagarActuadores();

  Serial.println();
  Serial.println("======================================");
  Serial.println(" SISTEMA DE DETECCION DE ROPA SECA");
  Serial.println("======================================");
  Serial.println("[FSM] Estado inicial: ESPERA");

  iniciarWiFi();

  mqttClient.setServer(MQTT_BROKER, MQTT_PUERTO);
  mqttClient.setCallback(recibirMensajeMQTT);

  colaEventos = xQueueCreate(CANTIDAD_EVENTOS_COLA, sizeof(Evento));

  if (colaEventos == nullptr) {
    Serial.println("[ERROR] No se pudo crear la cola.");
    return;
  }

  xTaskCreate(
    tareaSensores,
    "TareaSensores",
    TAMANIO_PILA_SENSORES,
    nullptr,
    PRIORIDAD_TAREA,
    nullptr
  );

  xTaskCreate(
    tareaFSM,
    "TareaFSM",
    TAMANIO_PILA_FSM,
    nullptr,
    PRIORIDAD_TAREA,
    nullptr
  );
}

// ======================================================
// LOOP
// ======================================================

void loop()
{
}

// ======================================================
// WIFI
// ======================================================

void iniciarWiFi()
{
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  Serial.println("[WIFI] Iniciando conexion...");
}

// ======================================================
// MQTT
// ======================================================

void actualizarMQTT()
{
  if (WiFi.status() != WL_CONNECTED) {
    return;
  }

  if (mqttClient.connected()) {
    mqttClient.loop();
    return;
  }

  unsigned long ahora = millis();

  if (ahora - ultimoIntentoMQTTMs < PERIODO_RECONEXION_MQTT_MS) {
    return;
  }

  ultimoIntentoMQTTMs = ahora;

  Serial.println("[MQTT] Intentando conectar...");

  if (mqttClient.connect(MQTT_CLIENT_ID)) {
    Serial.println("[MQTT] Conectado al broker.");

    if (mqttClient.subscribe(MQTT_TOPIC_COMANDO)) {
      Serial.println("[MQTT] Suscripto al topic de comandos.");
    } else {
      Serial.println("[MQTT] No se pudo suscribir al topic de comandos.");
    }

    publicarConexionMQTT();
  } else {
    Serial.println("[MQTT] No se pudo conectar.");
  }
}

// ======================================================
// RECIBIR MQTT
// ======================================================

void recibirMensajeMQTT(char *topic, byte *payload, unsigned int length)
{
  if (strcmp(topic, MQTT_TOPIC_COMANDO) != 0) {
    return;
  }

  String comando;

  for (unsigned int i = 0; i < length; i++) {
    comando += static_cast<char>(payload[i]);
  }

  comando.trim();

  Serial.print("[MQTT] Comando recibido: ");
  Serial.println(comando);

  Evento evento;

  if (comando == MQTT_COMANDO_INICIAR) {
    evento = Evento::COMANDO_INICIAR;
  } else if (comando == MQTT_COMANDO_FINALIZAR) {
    evento = Evento::COMANDO_FINALIZAR;
  } else {
    Serial.println("[MQTT] Comando desconocido.");
    return;
  }

  if (xQueueSend(colaEventos, &evento, 0) != pdTRUE) {
    Serial.println("[MQTT] No se pudo agregar el comando a la cola.");
  }
}

// ======================================================
// PUBLICAR ESTADO MQTT
// ======================================================

void publicarEstadoMQTT()
{
  if (!mqttClient.connected()) {
    Serial.println("[MQTT] Estado no enviado: sin conexion.");
    return;
  }

  const char *mensaje = nombreEstado(estadoActual);

  if (!mqttClient.publish(MQTT_TOPIC_ESTADO, mensaje)) {
    Serial.println("[MQTT] Error al publicar estado.");
    return;
  }

  Serial.print("[MQTT] Estado publicado: ");
  Serial.println(mensaje);
}

// ======================================================
// PUBLICAR SENSOR MQTT
// ======================================================

void actualizarPublicacionSensorMQTT()
{
  if (estadoActual == Estado::ESPERA || !mqttClient.connected()) {
    return;
  }

  unsigned long ahora = millis();

  if (ahora - ultimaPublicacionSensorMs < PERIODO_PUBLICACION_SENSOR_MS) {
    return;
  }

  ultimaPublicacionSensorMs = ahora;

  publicarSensorMQTT();
}

void publicarSensorMQTT()
{
  char mensaje[16];

  snprintf(
    mensaje,
    sizeof(mensaje),
    "%d",
    sensores.humedadGeneralPct
  );

  if (!mqttClient.publish(MQTT_TOPIC_SENSOR, mensaje)) {
    Serial.println("[MQTT] Error al publicar humedad.");
    return;
  }

  Serial.print("[MQTT] Humedad publicada: ");
  Serial.print(mensaje);
  Serial.println("%");
}

// ======================================================
// PUBLICAR CONEXION MQTT
// ======================================================

void actualizarPublicacionConexionMQTT()
{
  if (!mqttClient.connected()) {
    return;
  }

  unsigned long ahora = millis();

  if (ahora - ultimaPublicacionConexionMs < PERIODO_PUBLICACION_CONEXION_MS) {
    return;
  }

  publicarConexionMQTT();
}

void publicarConexionMQTT()
{
  if (!mqttClient.connected()) {
    return;
  }

  if (!mqttClient.publish(MQTT_TOPIC_CONEXION, MQTT_MENSAJE_ONLINE)) {
    Serial.println("[MQTT] Error al publicar conexion.");
    return;
  }

  ultimaPublicacionConexionMs = millis();

  Serial.println("[MQTT] Conexion publicada: ONLINE");
}

// ======================================================
// TAREA SENSORES
// ======================================================

void tareaSensores(void *parametros)
{
  while (true) {
    leerSensores();

    Evento evento = generarEvento();

    xQueueSend(colaEventos, &evento, portMAX_DELAY);

    unsigned long ahora = millis();

    if (ahora - ultimoLogMs >= PERIODO_LOG_MS) {
      ultimoLogMs = ahora;
      mostrarLecturas();
    }

    vTaskDelay(pdMS_TO_TICKS(PERIODO_LECTURA_MS));
  }
}

// ======================================================
// TAREA FSM
// ======================================================

void tareaFSM(void *parametros)
{
  Evento evento;

  while (true) {
    actualizarMQTT();
    actualizarPublicacionConexionMQTT();
    actualizarPublicacionSensorMQTT();

    if (xQueueReceive(
          colaEventos,
          &evento,
          pdMS_TO_TICKS(PERIODO_LECTURA_MS)
        )) {
      ejecutarFSM(evento);
    }
  }
}

// ======================================================
// LECTURA DE SENSORES
// ======================================================

void leerSensores()
{
  sensores.adcPrenda1 = analogRead(PIN_BROCHE_1);
  sensores.adcPrenda2 = analogRead(PIN_BROCHE_2);

  sensores.humedadPrenda1Pct = calcularHumedad(sensores.adcPrenda1);
  sensores.humedadPrenda2Pct = calcularHumedad(sensores.adcPrenda2);

  sensores.humedadGeneralPct = max(
    sensores.humedadPrenda1Pct,
    sensores.humedadPrenda2Pct
  );

  sensores.progresoSecadoPct = calcularProgresoSecado(
    sensores.humedadGeneralPct
  );

  sensores.adcLluvia = analogRead(PIN_LLUVIA);

  sensores.lluvia = sensores.adcLluvia >= UMBRAL_LLUVIA_ADC;

  leerBoton();
}

// ======================================================
// BOTON
// ======================================================

void leerBoton()
{
  sensores.botonCicloPulsado = false;

  bool lecturaActual = digitalRead(PIN_BOTON_CICLO);

  if (lecturaActual != ultimaLecturaBoton) {
    ultimoCambioBotonMs = millis();
    ultimaLecturaBoton = lecturaActual;
  }

  if (millis() - ultimoCambioBotonMs < ANTIRREBOTE_BOTON_MS) {
    return;
  }

  if (lecturaActual == estadoEstableBoton) {
    return;
  }

  estadoEstableBoton = lecturaActual;

  if (estadoEstableBoton == LOW) {
    sensores.botonCicloPulsado = true;
  }
}

// ======================================================
// CALCULAR HUMEDAD
// ======================================================

int calcularHumedad(int lecturaADC)
{
  int humedad = map(
    lecturaADC,
    ADC_MINIMO,
    ADC_MAXIMO,
    PORCENTAJE_MAXIMO,
    PORCENTAJE_MINIMO
  );

  return constrain(
    humedad,
    PORCENTAJE_MINIMO,
    PORCENTAJE_MAXIMO
  );
}

// ======================================================
// CALCULAR PROGRESO SECADO
// ======================================================

int calcularProgresoSecado(int humedadGeneral)
{
  int progreso = map(
    humedadGeneral,
    PORCENTAJE_MAXIMO,
    UMBRAL_SECO_PCT,
    PORCENTAJE_MINIMO,
    PORCENTAJE_MAXIMO
  );

  return constrain(
    progreso,
    PORCENTAJE_MINIMO,
    PORCENTAJE_MAXIMO
  );
}

// ======================================================
// ROPA SECA
// ======================================================

bool todasLasPrendasSecas()
{
  bool prenda1Seca = sensores.humedadPrenda1Pct <= UMBRAL_SECO_PCT;
  bool prenda2Seca = sensores.humedadPrenda2Pct <= UMBRAL_SECO_PCT;

  return prenda1Seca && prenda2Seca;
}

// ======================================================
// GENERACION DE EVENTOS
// ======================================================

Evento generarEvento()
{
  if (sensores.botonCicloPulsado) {
    return Evento::BOTON_CICLO;
  }

  if (sensores.lluvia) {
    return Evento::LLUVIA_DETECTADA;
  }

  if (todasLasPrendasSecas()) {
    return Evento::ROPA_SECA;
  }

  return Evento::ROPA_HUMEDA;
}

// ======================================================
// MAQUINA DE ESTADOS
// ======================================================

void ejecutarFSM(Evento evento)
{
  switch (estadoActual) {

    case Estado::ESPERA:

      switch (evento) {

        case Evento::BOTON_CICLO:
        case Evento::COMANDO_INICIAR:

          actualizarLedSecado();
          apagarBuzzer();

          Serial.println("[INFO] Monitoreo iniciado.");

          cambiarEstado(Estado::MONITOREANDO_SECADO);

          break;

        default:
          break;
      }

      break;

    case Estado::MONITOREANDO_SECADO:

      switch (evento) {

        case Evento::BOTON_CICLO:
        case Evento::COMANDO_FINALIZAR:

          apagarActuadores();

          Serial.println("[INFO] Ciclo finalizado.");

          cambiarEstado(Estado::ESPERA);

          break;

        case Evento::LLUVIA_DETECTADA:

          actualizarLedSecado();

          iniciarBuzzer(FRECUENCIA_BUZZER_LLUVIA_HZ);

          notificarLluvia();

          cambiarEstado(Estado::NOTIFICANDO_LLUVIA);

          break;

        case Evento::ROPA_SECA:

          encenderLedSeco();

          iniciarBuzzer(FRECUENCIA_BUZZER_SECA_HZ);

          notificarRopaSeca();

          cambiarEstado(Estado::NOTIFICANDO_ROPA_SECA);

          break;

        case Evento::ROPA_HUMEDA:

          actualizarLedSecado();
          apagarBuzzer();

          break;

        default:
          break;
      }

      break;

    case Estado::NOTIFICANDO_ROPA_SECA:

      switch (evento) {

        case Evento::BOTON_CICLO:
        case Evento::COMANDO_FINALIZAR:

          apagarActuadores();

          Serial.println("[INFO] Ciclo finalizado.");

          cambiarEstado(Estado::ESPERA);

          break;

        case Evento::LLUVIA_DETECTADA:

          actualizarLedSecado();

          iniciarBuzzer(FRECUENCIA_BUZZER_LLUVIA_HZ);

          notificarLluvia();

          cambiarEstado(Estado::NOTIFICANDO_LLUVIA);

          break;

        case Evento::ROPA_SECA:

          encenderLedSeco();

          actualizarBuzzer(
            PERIODO_BUZZER_SECA_MS,
            FRECUENCIA_BUZZER_SECA_HZ
          );

          break;

        case Evento::ROPA_HUMEDA:

          apagarBuzzer();
          actualizarLedSecado();

          Serial.println("[INFO] La ropa registra humedad nuevamente.");

          cambiarEstado(Estado::MONITOREANDO_SECADO);

          break;

        default:
          break;
      }

      break;

    case Estado::NOTIFICANDO_LLUVIA:

      switch (evento) {

        case Evento::BOTON_CICLO:
        case Evento::COMANDO_FINALIZAR:

          apagarActuadores();

          Serial.println("[INFO] Ciclo finalizado.");

          cambiarEstado(Estado::ESPERA);

          break;

        case Evento::LLUVIA_DETECTADA:

          actualizarLedSecado();

          actualizarBuzzer(
            PERIODO_BUZZER_LLUVIA_MS,
            FRECUENCIA_BUZZER_LLUVIA_HZ
          );

          break;

        case Evento::ROPA_SECA:

          encenderLedSeco();

          iniciarBuzzer(FRECUENCIA_BUZZER_SECA_HZ);

          notificarRopaSeca();

          cambiarEstado(Estado::NOTIFICANDO_ROPA_SECA);

          break;

        case Evento::ROPA_HUMEDA:

          apagarBuzzer();
          actualizarLedSecado();

          Serial.println("[INFO] Finalizo la lluvia.");

          cambiarEstado(Estado::MONITOREANDO_SECADO);

          break;

        default:
          break;
      }

      break;
  }
}

// ======================================================
// CAMBIO DE ESTADO
// ======================================================

void cambiarEstado(Estado nuevoEstado)
{
  if (estadoActual == nuevoEstado) {
    return;
  }

  estadoActual = nuevoEstado;

  Serial.print("[FSM] Nuevo estado: ");
  Serial.println(nombreEstado(nuevoEstado));

  publicarEstadoMQTT();
}

// ======================================================
// LED - PROGRESO SECADO
// ======================================================

void actualizarLedSecado()
{
  int pwm = map(
    sensores.progresoSecadoPct,
    PORCENTAJE_MINIMO,
    PORCENTAJE_MAXIMO,
    PWM_MINIMO,
    PWM_MAXIMO
  );

  analogWrite(PIN_LED, pwm);
}

// ======================================================
// LED - ROPA SECA
// ======================================================

void encenderLedSeco()
{
  analogWrite(PIN_LED, PWM_MAXIMO);
}

// ======================================================
// LED - APAGAR
// ======================================================

void apagarLed()
{
  analogWrite(PIN_LED, PWM_MINIMO);
}

// ======================================================
// INICIAR BUZZER
// ======================================================

void iniciarBuzzer(unsigned int frecuencia)
{
  ultimoCambioBuzzerMs = millis();
  buzzerEncendido = true;

  encenderBuzzer(frecuencia);
}

// ======================================================
// ACTUALIZAR BUZZER
// ======================================================

void actualizarBuzzer(
  unsigned long periodo,
  unsigned int frecuencia
)
{
  unsigned long ahora = millis();

  if (ahora - ultimoCambioBuzzerMs < periodo) {
    return;
  }

  ultimoCambioBuzzerMs = ahora;

  buzzerEncendido = !buzzerEncendido;

  if (buzzerEncendido) {
    encenderBuzzer(frecuencia);
  } else {
    apagarBuzzer();
  }
}

// ======================================================
// ENCENDER BUZZER
// ======================================================

void encenderBuzzer(unsigned int frecuencia)
{
  tone(PIN_BUZZER, frecuencia);
}

// ======================================================
// APAGAR BUZZER
// ======================================================

void apagarBuzzer()
{
  noTone(PIN_BUZZER);

  buzzerEncendido = false;
}

// ======================================================
// APAGAR ACTUADORES
// ======================================================

void apagarActuadores()
{
  apagarLed();
  apagarBuzzer();
}

// ======================================================
// NOTIFICACION ROPA SECA
// ======================================================

void notificarRopaSeca()
{
  Serial.println("[NOTIFICACION] Todas las prendas estan secas.");
}

// ======================================================
// NOTIFICACION LLUVIA
// ======================================================

void notificarLluvia()
{
  Serial.println("[NOTIFICACION] Se detecto lluvia.");
}

// ======================================================
// NOMBRE ESTADO
// ======================================================

const char *nombreEstado(Estado estado)
{
  switch (estado) {

    case Estado::ESPERA:
      return "ESPERA";

    case Estado::MONITOREANDO_SECADO:
      return "MONITOREANDO_SECADO";

    case Estado::NOTIFICANDO_ROPA_SECA:
      return "ROPA_SECA";

    case Estado::NOTIFICANDO_LLUVIA:
      return "LLUVIA";
  }

  return "DESCONOCIDO";
}

// ======================================================
// LOG
// ======================================================

void mostrarLecturas()
{
  Serial.print("[SENSORES] ");

  Serial.print("Prenda 1: ");
  Serial.print(sensores.humedadPrenda1Pct);
  Serial.print("%");

  Serial.print(" | Prenda 2: ");
  Serial.print(sensores.humedadPrenda2Pct);
  Serial.print("%");

  Serial.print(" | Humedad general: ");
  Serial.print(sensores.humedadGeneralPct);
  Serial.print("%");

  Serial.print(" | Secado: ");
  Serial.print(sensores.progresoSecadoPct);
  Serial.print("%");

  Serial.print(" | Lluvia ADC: ");
  Serial.print(sensores.adcLluvia);

  Serial.print(" | Lluvia: ");

  if (sensores.lluvia) {
    Serial.print("SI");
  } else {
    Serial.print("NO");
  }

  Serial.print(" | Estado: ");
  Serial.println(nombreEstado(estadoActual));
}