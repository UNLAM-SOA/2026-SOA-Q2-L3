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

// ======================================================
// BUZZER
// ======================================================

constexpr unsigned int FRECUENCIA_BUZZER_SECA_HZ = 900;
constexpr unsigned int FRECUENCIA_BUZZER_LLUVIA_HZ = 1600;

// ======================================================
// WIFI
// ======================================================

constexpr char WIFI_SSID[] = "Wokwi-GUEST";
constexpr char WIFI_PASSWORD[] = "";

// ======================================================
// MQTT
// ======================================================

constexpr char MQTT_BROKER[] = "test.mosquitto.org";
constexpr uint16_t MQTT_PUERTO = 1883;

constexpr char MQTT_CLIENT_ID[] =
  "unlam-soa-ropa-seca-esp32-2026";

constexpr char MQTT_TOPIC_ESTADO[] =
  "unlam/soa/ropa-seca-2026/estado";

constexpr char MQTT_MENSAJE_ROPA_SECA[] =
  "ROPA_SECA";

constexpr char MQTT_MENSAJE_LLUVIA[] =
  "LLUVIA";

// ======================================================
// FREERTOS
// ======================================================

constexpr uint16_t TAMANIO_PILA_SENSORES = 2048;
constexpr uint16_t TAMANIO_PILA_FSM = 6144;

constexpr UBaseType_t PRIORIDAD_TAREA = 1;
constexpr uint8_t CANTIDAD_MENSAJES_COLA = 10;

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

// ======================================================
// MENSAJE ENTRE TAREAS
// ======================================================

struct MensajeFSM {
  Evento evento;
  int progresoSecadoPct;
};

// ======================================================
// VARIABLES
// ======================================================

LecturasSensores sensores;

Estado estadoActual =
  Estado::ESPERA;

QueueHandle_t colaFSM;

WiFiClient wifiClient;
PubSubClient mqttClient(wifiClient);

unsigned long ultimoIntentoMQTTMs = 0;
unsigned long ultimoLogMs = 0;
unsigned long ultimoCambioBuzzerMs = 0;
unsigned long ultimoCambioBotonMs = 0;

bool wifiConectadoAnteriormente = false;

bool ultimaLecturaBoton = HIGH;
bool estadoEstableBoton = HIGH;

bool buzzerEncendido = false;

// ======================================================
// DECLARACIONES
// ======================================================

void tareaSensores(void *parametros);
void tareaFSM(void *parametros);

void iniciarWiFi();
void actualizarWiFi();
void actualizarMQTT();

void publicarEstadoMQTT(
  const char *mensaje
);

void leerSensores();
void leerBoton();

int calcularHumedad(
  int lecturaADC
);

int calcularProgresoSecado(
  int humedadGeneral
);

bool todasLasPrendasSecas();

Evento generarEvento();

void ejecutarFSM(
  const MensajeFSM &mensaje
);

void cambiarEstado(
  Estado nuevoEstado
);

void actualizarLedSecado(
  int progresoSecadoPct
);

void encenderLedSeco();
void apagarLed();

void iniciarBuzzer(
  unsigned int frecuencia
);

void actualizarBuzzer(
  unsigned long periodo,
  unsigned int frecuencia
);

void encenderBuzzer(
  unsigned int frecuencia
);

void apagarBuzzer();
void apagarActuadores();

void notificarRopaSeca();
void notificarLluvia();

void mostrarLecturas();

const char *nombreEstado(
  Estado estado
);

// ======================================================
// SETUP
// ======================================================

void setup()
{
  Serial.begin(115200);

  pinMode(PIN_BROCHE_1, INPUT);
  pinMode(PIN_BROCHE_2, INPUT);
  pinMode(PIN_LLUVIA, INPUT);

  pinMode(
    PIN_BOTON_CICLO,
    INPUT_PULLUP
  );

  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);

  apagarActuadores();

  Serial.println();
  Serial.println(
    "======================================"
  );
  Serial.println(
    " SISTEMA DE DETECCION DE ROPA SECA"
  );
  Serial.println(
    "======================================"
  );
  Serial.println(
    "[FSM] Estado inicial: ESPERA"
  );

  iniciarWiFi();

  mqttClient.setServer(
    MQTT_BROKER,
    MQTT_PUERTO
  );

  colaFSM = xQueueCreate(
    CANTIDAD_MENSAJES_COLA,
    sizeof(MensajeFSM)
  );

  if (colaFSM == nullptr) {
    Serial.println(
      "[ERROR] No se pudo crear la cola."
    );

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
  WiFi.mode(WIFI_STA);

  WiFi.begin(
    WIFI_SSID,
    WIFI_PASSWORD,
    6
  );

  Serial.println(
    "[WIFI] Iniciando conexion..."
  );
}

void actualizarWiFi()
{
  bool conectado =
    WiFi.status() == WL_CONNECTED;

  if (
    conectado
    && !wifiConectadoAnteriormente
  ) {
    Serial.print(
      "[WIFI] Conectado. IP: "
    );

    Serial.println(
      WiFi.localIP()
    );
  }

  if (
    !conectado
    && wifiConectadoAnteriormente
  ) {
    Serial.println(
      "[WIFI] Conexion perdida."
    );
  }

  wifiConectadoAnteriormente =
    conectado;
}

// ======================================================
// MQTT
// ======================================================

void actualizarMQTT()
{
  if (
    WiFi.status()
    != WL_CONNECTED
  ) {
    return;
  }

  if (
    mqttClient.connected()
  ) {
    mqttClient.loop();
    return;
  }

  unsigned long ahora =
    millis();

  if (
    ahora - ultimoIntentoMQTTMs
    < PERIODO_RECONEXION_MQTT_MS
  ) {
    return;
  }

  ultimoIntentoMQTTMs =
    ahora;

  Serial.println(
    "[MQTT] Intentando conectar..."
  );

  if (
    mqttClient.connect(
      MQTT_CLIENT_ID
    )
  ) {
    Serial.println(
      "[MQTT] Conectado al broker."
    );

    return;
  }

  Serial.print(
    "[MQTT] Error. Estado: "
  );

  Serial.println(
    mqttClient.state()
  );
}

void publicarEstadoMQTT(
  const char *mensaje
)
{
  if (
    !mqttClient.connected()
  ) {
    Serial.println(
      "[MQTT] Mensaje no enviado: sin conexion."
    );

    return;
  }

  if (
    !mqttClient.publish(
      MQTT_TOPIC_ESTADO,
      mensaje
    )
  ) {
    Serial.println(
      "[MQTT] Error al publicar."
    );

    return;
  }

  Serial.print(
    "[MQTT] Publicado: "
  );

  Serial.println(
    mensaje
  );
}

// ======================================================
// TAREA SENSORES
// ======================================================

void tareaSensores(
  void *parametros
)
{
  while (true) {
    leerSensores();

    MensajeFSM mensaje = {
      generarEvento(),
      sensores.progresoSecadoPct
    };

    xQueueSend(
      colaFSM,
      &mensaje,
      portMAX_DELAY
    );

    unsigned long ahora =
      millis();

    if (
      ahora - ultimoLogMs
      >= PERIODO_LOG_MS
    ) {
      ultimoLogMs =
        ahora;

      mostrarLecturas();
    }

    vTaskDelay(
      pdMS_TO_TICKS(
        PERIODO_LECTURA_MS
      )
    );
  }
}

// ======================================================
// TAREA FSM
// ======================================================

void tareaFSM(
  void *parametros
)
{
  MensajeFSM mensaje;

  while (true) {
    actualizarWiFi();
    actualizarMQTT();

    if (
      xQueueReceive(
        colaFSM,
        &mensaje,
        pdMS_TO_TICKS(
          PERIODO_LECTURA_MS
        )
      )
    ) {
      ejecutarFSM(
        mensaje
      );
    }
  }
}

// ======================================================
// LECTURA DE SENSORES
// ======================================================

void leerSensores()
{
  sensores.adcPrenda1 =
    analogRead(PIN_BROCHE_1);

  sensores.adcPrenda2 =
    analogRead(PIN_BROCHE_2);

  sensores.humedadPrenda1Pct =
    calcularHumedad(
      sensores.adcPrenda1
    );

  sensores.humedadPrenda2Pct =
    calcularHumedad(
      sensores.adcPrenda2
    );

  sensores.humedadGeneralPct =
    max(
      sensores.humedadPrenda1Pct,
      sensores.humedadPrenda2Pct
    );

  sensores.progresoSecadoPct =
    calcularProgresoSecado(
      sensores.humedadGeneralPct
    );

  sensores.adcLluvia =
    analogRead(PIN_LLUVIA);

  sensores.lluvia =
    sensores.adcLluvia
      >= UMBRAL_LLUVIA_ADC;

  leerBoton();
}

// ======================================================
// BOTON
// ======================================================

void leerBoton()
{
  sensores.botonCicloPulsado =
    false;

  bool lecturaActual =
    digitalRead(
      PIN_BOTON_CICLO
    );

  if (
    lecturaActual
    != ultimaLecturaBoton
  ) {
    ultimoCambioBotonMs =
      millis();

    ultimaLecturaBoton =
      lecturaActual;
  }

  if (
    millis() - ultimoCambioBotonMs
    < ANTIRREBOTE_BOTON_MS
  ) {
    return;
  }

  if (
    lecturaActual
    == estadoEstableBoton
  ) {
    return;
  }

  estadoEstableBoton =
    lecturaActual;

  if (
    estadoEstableBoton
    == LOW
  ) {
    sensores.botonCicloPulsado =
      true;
  }
}

// ======================================================
// HUMEDAD
// ======================================================

int calcularHumedad(
  int lecturaADC
)
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
// PROGRESO SECADO
// ======================================================

int calcularProgresoSecado(
  int humedadGeneral
)
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
  bool prenda1Seca =
    sensores.humedadPrenda1Pct
      <= UMBRAL_SECO_PCT;

  bool prenda2Seca =
    sensores.humedadPrenda2Pct
      <= UMBRAL_SECO_PCT;

  return
    prenda1Seca
    && prenda2Seca;
}

// ======================================================
// EVENTOS
// ======================================================

Evento generarEvento()
{
  if (
    sensores.botonCicloPulsado
  ) {
    return Evento::BOTON_CICLO;
  }

  if (
    sensores.lluvia
  ) {
    return Evento::LLUVIA_DETECTADA;
  }

  if (
    todasLasPrendasSecas()
  ) {
    return Evento::ROPA_SECA;
  }

  return Evento::ROPA_HUMEDA;
}

// ======================================================
// MAQUINA DE ESTADOS
// ======================================================

void ejecutarFSM(
  const MensajeFSM &mensaje
)
{
  Evento evento =
    mensaje.evento;

  int progreso =
    mensaje.progresoSecadoPct;

  switch (estadoActual) {

    case Estado::ESPERA:

      switch (evento) {

        case Evento::BOTON_CICLO:

          actualizarLedSecado(
            progreso
          );

          apagarBuzzer();

          Serial.println(
            "[INFO] Monitoreo iniciado."
          );

          cambiarEstado(
            Estado::MONITOREANDO_SECADO
          );

          break;

        default:
          break;
      }

      break;

    case Estado::MONITOREANDO_SECADO:

      switch (evento) {

        case Evento::BOTON_CICLO:

          apagarActuadores();

          Serial.println(
            "[INFO] Ciclo finalizado."
          );

          cambiarEstado(
            Estado::ESPERA
          );

          break;

        case Evento::LLUVIA_DETECTADA:

          actualizarLedSecado(
            progreso
          );

          iniciarBuzzer(
            FRECUENCIA_BUZZER_LLUVIA_HZ
          );

          notificarLluvia();

          publicarEstadoMQTT(
            MQTT_MENSAJE_LLUVIA
          );

          cambiarEstado(
            Estado::NOTIFICANDO_LLUVIA
          );

          break;

        case Evento::ROPA_SECA:

          encenderLedSeco();

          iniciarBuzzer(
            FRECUENCIA_BUZZER_SECA_HZ
          );

          notificarRopaSeca();

          publicarEstadoMQTT(
            MQTT_MENSAJE_ROPA_SECA
          );

          cambiarEstado(
            Estado::NOTIFICANDO_ROPA_SECA
          );

          break;

        case Evento::ROPA_HUMEDA:

          actualizarLedSecado(
            progreso
          );

          apagarBuzzer();

          break;
      }

      break;

    case Estado::NOTIFICANDO_ROPA_SECA:

      switch (evento) {

        case Evento::BOTON_CICLO:

          apagarActuadores();

          Serial.println(
            "[INFO] Ciclo finalizado."
          );

          cambiarEstado(
            Estado::ESPERA
          );

          break;

        case Evento::LLUVIA_DETECTADA:

          actualizarLedSecado(
            progreso
          );

          iniciarBuzzer(
            FRECUENCIA_BUZZER_LLUVIA_HZ
          );

          notificarLluvia();

          publicarEstadoMQTT(
            MQTT_MENSAJE_LLUVIA
          );

          cambiarEstado(
            Estado::NOTIFICANDO_LLUVIA
          );

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

          actualizarLedSecado(
            progreso
          );

          Serial.println(
            "[INFO] La ropa registra humedad nuevamente."
          );

          cambiarEstado(
            Estado::MONITOREANDO_SECADO
          );

          break;
      }

      break;

    case Estado::NOTIFICANDO_LLUVIA:

      switch (evento) {

        case Evento::BOTON_CICLO:

          apagarActuadores();

          Serial.println(
            "[INFO] Ciclo finalizado."
          );

          cambiarEstado(
            Estado::ESPERA
          );

          break;

        case Evento::LLUVIA_DETECTADA:

          actualizarLedSecado(
            progreso
          );

          actualizarBuzzer(
            PERIODO_BUZZER_LLUVIA_MS,
            FRECUENCIA_BUZZER_LLUVIA_HZ
          );

          break;

        case Evento::ROPA_SECA:

          encenderLedSeco();

          iniciarBuzzer(
            FRECUENCIA_BUZZER_SECA_HZ
          );

          notificarRopaSeca();

          publicarEstadoMQTT(
            MQTT_MENSAJE_ROPA_SECA
          );

          cambiarEstado(
            Estado::NOTIFICANDO_ROPA_SECA
          );

          break;

        case Evento::ROPA_HUMEDA:

          apagarBuzzer();

          actualizarLedSecado(
            progreso
          );

          Serial.println(
            "[INFO] Finalizo la lluvia."
          );

          cambiarEstado(
            Estado::MONITOREANDO_SECADO
          );

          break;
      }

      break;
  }
}

// ======================================================
// CAMBIO DE ESTADO
// ======================================================

void cambiarEstado(
  Estado nuevoEstado
)
{
  estadoActual =
    nuevoEstado;

  Serial.print(
    "[FSM] Nuevo estado: "
  );

  Serial.println(
    nombreEstado(
      nuevoEstado
    )
  );
}

// ======================================================
// LED
// ======================================================

void actualizarLedSecado(
  int progresoSecadoPct
)
{
  int pwm = map(
    progresoSecadoPct,
    PORCENTAJE_MINIMO,
    PORCENTAJE_MAXIMO,
    PWM_MINIMO,
    PWM_MAXIMO
  );

  analogWrite(
    PIN_LED,
    pwm
  );
}

void encenderLedSeco()
{
  analogWrite(
    PIN_LED,
    PWM_MAXIMO
  );
}

void apagarLed()
{
  analogWrite(
    PIN_LED,
    PWM_MINIMO
  );
}

// ======================================================
// BUZZER
// ======================================================

void iniciarBuzzer(
  unsigned int frecuencia
)
{
  ultimoCambioBuzzerMs =
    millis();

  buzzerEncendido =
    true;

  encenderBuzzer(
    frecuencia
  );
}

void actualizarBuzzer(
  unsigned long periodo,
  unsigned int frecuencia
)
{
  unsigned long ahora =
    millis();

  if (
    ahora - ultimoCambioBuzzerMs
    < periodo
  ) {
    return;
  }

  ultimoCambioBuzzerMs =
    ahora;

  buzzerEncendido =
    !buzzerEncendido;

  if (
    buzzerEncendido
  ) {
    encenderBuzzer(
      frecuencia
    );
  } else {
    apagarBuzzer();
  }
}

void encenderBuzzer(
  unsigned int frecuencia
)
{
  tone(
    PIN_BUZZER,
    frecuencia
  );
}

void apagarBuzzer()
{
  noTone(
    PIN_BUZZER
  );

  buzzerEncendido =
    false;
}

// ======================================================
// ACTUADORES
// ======================================================

void apagarActuadores()
{
  apagarLed();
  apagarBuzzer();
}

// ======================================================
// NOTIFICACIONES
// ======================================================

void notificarRopaSeca()
{
  Serial.println(
    "[NOTIFICACION] Todas las prendas estan secas."
  );
}

void notificarLluvia()
{
  Serial.println(
    "[NOTIFICACION] Se detecto lluvia."
  );
}

// ======================================================
// NOMBRE ESTADO
// ======================================================

const char *nombreEstado(
  Estado estado
)
{
  switch (estado) {

    case Estado::ESPERA:
      return "ESPERA";

    case Estado::MONITOREANDO_SECADO:
      return "MONITOREANDO_SECADO";

    case Estado::NOTIFICANDO_ROPA_SECA:
      return "NOTIFICANDO_ROPA_SECA";

    case Estado::NOTIFICANDO_LLUVIA:
      return "NOTIFICANDO_LLUVIA";
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
  Serial.print(
    sensores.humedadPrenda1Pct
  );
  Serial.print("%");

  Serial.print(" | Prenda 2: ");
  Serial.print(
    sensores.humedadPrenda2Pct
  );
  Serial.print("%");

  Serial.print(
    " | Humedad general: "
  );
  Serial.print(
    sensores.humedadGeneralPct
  );
  Serial.print("%");

  Serial.print(
    " | Secado: "
  );
  Serial.print(
    sensores.progresoSecadoPct
  );
  Serial.print("%");

  Serial.print(
    " | Lluvia ADC: "
  );
  Serial.print(
    sensores.adcLluvia
  );

  Serial.print(
    " | Lluvia: "
  );

  if (
    sensores.lluvia
  ) {
    Serial.print("SI");
  } else {
    Serial.print("NO");
  }

  Serial.print(
    " | Estado: "
  );

  Serial.println(
    nombreEstado(
      estadoActual
    )
  );
}