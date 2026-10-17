#include <stdio.h>

// Se ejecutan las funciones del firmware real, con GPIO/red/RTOS sustituidos.
#include "../Proyecto_con_Vibecoding.ino"
#include "../Intercambio.cpp"

uint32_t relojPrueba = 0;
int pwmPrueba = 0;
bool tonoPrueba = false;
bool botonPrueba = false;
void (*antesDeBloquear)() = nullptr;
unsigned bloqueosPrueba = 0;
SerialPrueba Serial;

bool iniciarComunicacion() { return false; }

constexpr int ADC_SIN_LLUVIA = LLUVIA_CON_ADC_ALTO ? 0 : ADC_MAXIMO;
constexpr int ADC_CON_LLUVIA = UMBRAL_LLUVIA_ADC;

void reiniciarPrueba()
{
  relojPrueba = 0;
  estadoActual = Estado::ESPERA;
  sensores = LecturasSensores{};
  condiciones = CondicionesCiclo{};
  alertaSonora = AlertaSonora{};
  buzzerEncendido = false;
  lecturaValida = false;
  revisionEstado = 0;
  numeroCiclo = 0;
  pulsacionesProcesadas = 0;
  ultimaInstantanea = InstantaneaSistema{};
  comandos = BuzonComandos{};
  botonCompartido = BotonAntirrebote{};
  pulsacionesAceptadas = 0;
  botonPrueba = false;
  antesDeBloquear = nullptr;
  assert(bloqueosPrueba == 0);
  apagarActuadores();
}

void paso(uint32_t ahora, int h1, int h2, int lluvia, uint32_t pulsaciones,
          Evento comando = Evento::NINGUNO)
{
  relojPrueba = ahora;
  MensajeSensores muestra = {};
  muestra.lecturas.humedadPrenda1 = h1;
  muestra.lecturas.humedadPrenda2 = h2;
  muestra.lecturas.humedadGeneral = h1 > h2 ? h1 : h2;
  muestra.lecturas.progreso = calcularProgreso(muestra.lecturas.humedadGeneral);
  muestra.lecturas.adcLluvia = lluvia;
  muestra.pulsaciones = pulsaciones;
  // Los casos generales inyectan muestras ya aceptadas por el productor.
  // Las carreras de abajo usan muestrearPulsador() real y demoran su entrega.
  if (contadorPosterior(pulsaciones, pulsacionesAceptadas)) {
    pulsacionesAceptadas = pulsaciones;
  }
  procesarEntradas(&muestra, comando);
}

int probarFsm()
{
#define COMPROBAR(condicion) do { if (!(condicion)) return __LINE__; } while (false)
  reiniciarPrueba();
  paso(0, 0, 0, ADC_CON_LLUVIA, 0);
  paso(2000, 0, 0, ADC_CON_LLUVIA, 0);
  COMPROBAR(estadoActual == Estado::ESPERA && pwmPrueba == 0 && !tonoPrueba);
  procesarEntradas(nullptr, Evento::COMANDO_FINALIZAR);
  COMPROBAR(revisionEstado == 0);
  procesarEntradas(nullptr, Evento::COMANDO_INICIAR);
  COMPROBAR(estadoActual == Estado::MONITOREANDO_SECADO && !lecturaValida && pwmPrueba == 0);
  COMPROBAR(!leerEstado().lecturaValida && numeroCiclo == 1);
  procesarEntradas(nullptr, Evento::COMANDO_INICIAR);
  COMPROBAR(revisionEstado == 1 && numeroCiclo == 1);
  paso(2050, 0, 60, ADC_SIN_LLUVIA, 0);
  paso(3050, 0, 60, ADC_SIN_LLUVIA, 0);
  COMPROBAR(estadoActual == Estado::MONITOREANDO_SECADO && pwmPrueba == 127);
  COMPROBAR(leerEstado().humedad == 60 && leerEstado().lecturaValida);

  reiniciarPrueba();
  paso(0, 20, 20, ADC_CON_LLUVIA, 0, Evento::COMANDO_INICIAR);
  paso(1000, 20, 20, ADC_CON_LLUVIA, 0);
  COMPROBAR(estadoActual == Estado::NOTIFICANDO_LLUVIA && tonoPrueba && pwmPrueba == 255);
  const uint32_t revisionAlerta = revisionEstado;
  paso(1400, 20, 20, ADC_CON_LLUVIA, 0, Evento::COMANDO_INICIAR);
  COMPROBAR(revisionEstado == revisionAlerta && numeroCiclo == 1);
  COMPROBAR(alertaSonora.entrada == 1000 && !tonoPrueba);
  relojPrueba = 31000;
  procesarEntradas(nullptr, Evento::COMANDO_INICIAR);
  COMPROBAR(!tonoPrueba && estadoActual == Estado::NOTIFICANDO_LLUVIA);
  paso(32000, 20, 20, ADC_SIN_LLUVIA, 0);
  paso(33000, 20, 20, ADC_SIN_LLUVIA, 0);
  COMPROBAR(estadoActual == Estado::NOTIFICANDO_ROPA_SECA && tonoPrueba);
  COMPROBAR(alertaSonora.entrada == 33000);
  paso(34000, 25, 20, ADC_SIN_LLUVIA, 0);
  paso(35000, 25, 20, ADC_SIN_LLUVIA, 0);
  COMPROBAR(estadoActual == Estado::MONITOREANDO_SECADO && !tonoPrueba);

  const Evento alertas[] = {Evento::NINGUNO, Evento::ROPA_SECA, Evento::LLUVIA_DETECTADA};
  for (Evento alerta : alertas) {
    reiniciarPrueba();
    paso(0, 60, 60, ADC_SIN_LLUVIA, 0, Evento::COMANDO_INICIAR);
    ejecutarFSM(alerta);
    const Estado anterior = estadoActual;
    const uint32_t revision = revisionEstado;
    relojPrueba = 500;
    procesarEntradas(nullptr, Evento::COMANDO_INICIAR);
    COMPROBAR(estadoActual == anterior && revisionEstado == revision && numeroCiclo == 1);
    procesarEntradas(nullptr, Evento::COMANDO_FINALIZAR);
    COMPROBAR(estadoActual == Estado::ESPERA && pwmPrueba == 0 && !tonoPrueba);
    COMPROBAR(!leerEstado().lecturaValida);
    const uint32_t finalizada = revisionEstado;
    procesarEntradas(nullptr, Evento::COMANDO_FINALIZAR);
    COMPROBAR(revisionEstado == finalizada);
  }

  reiniciarPrueba();
  paso(0, 80, 80, ADC_SIN_LLUVIA, 1);
  COMPROBAR(estadoActual == Estado::MONITOREANDO_SECADO);
  paso(50, 80, 80, ADC_SIN_LLUVIA, 2, Evento::COMANDO_INICIAR);
  COMPROBAR(estadoActual == Estado::ESPERA); // Finalización local gana al inicio remoto.
  paso(100, 80, 80, ADC_SIN_LLUVIA, 3, Evento::COMANDO_FINALIZAR);
  COMPROBAR(estadoActual == Estado::ESPERA && pulsacionesProcesadas == 3);
  paso(150, 80, 80, ADC_SIN_LLUVIA, 3);
  COMPROBAR(estadoActual == Estado::ESPERA); // No reaparece la pulsación descartada.
  paso(200, 80, 80, ADC_SIN_LLUVIA, 4);
  paso(250, 80, 80, ADC_SIN_LLUVIA, 5, Evento::COMANDO_FINALIZAR);
  COMPROBAR(estadoActual == Estado::ESPERA && pwmPrueba == 0 && !tonoPrueba);

  reiniciarPrueba();
  paso(0, 20, 20, ADC_SIN_LLUVIA, 0, Evento::COMANDO_INICIAR);
  paso(1000, 20, 20, ADC_SIN_LLUVIA, 0);
  COMPROBAR(estadoActual == Estado::NOTIFICANDO_ROPA_SECA);
  procesarEntradas(nullptr, Evento::COMANDO_FINALIZAR);
  paso(1100, 22, 22, ADC_SIN_LLUVIA, 0, Evento::COMANDO_INICIAR);
  paso(2200, 22, 22, ADC_SIN_LLUVIA, 0);
  COMPROBAR(estadoActual == Estado::MONITOREANDO_SECADO && !condiciones.seca.valor);
  COMPROBAR(numeroCiclo == 2);
  paso(2250, 22, 22, ADC_SIN_LLUVIA, 2);
  COMPROBAR(estadoActual == Estado::ESPERA && numeroCiclo == 2);
#undef COMPROBAR
  return 0;
}

void recibirFinalizarAntesDelBloqueo()
{
  relojPrueba = 101;
  assert(entregarComando(Evento::COMANDO_FINALIZAR));
}

void aceptarPulsacionAntesDelCorte()
{
  relojPrueba = 50;
  assert(muestrearPulsador() == 1);
}

int probarIntercalados()
{
#define COMPROBAR(condicion) do { if (!(condicion)) return __LINE__; } while (false)
  reiniciarPrueba();
  paso(0, 80, 80, ADC_SIN_LLUVIA, 0, Evento::COMANDO_INICIAR);
  establecerConexionMqtt(true);
  relojPrueba = 100;
  antesDeBloquear = recibirFinalizarAntesDelBloqueo;
  const Evento orden = tomarComando(); // Otra tarea recibe a t=101 antes del lock.
  COMPROBAR(orden == Evento::COMANDO_FINALIZAR);
  procesarEntradas(nullptr, orden);
  COMPROBAR(estadoActual == Estado::ESPERA);
  COMPROBAR(tomarComando() == Evento::NINGUNO);
  COMPROBAR(entregarComando(Evento::COMANDO_INICIAR));
  relojPrueba += VIGENCIA_COMANDO_MS;
  COMPROBAR(tomarComando() == Evento::NINGUNO); // Caducidad genuina conservada.
  relojPrueba = UINT32_MAX - 20;
  COMPROBAR(entregarComando(Evento::COMANDO_FINALIZAR));
  relojPrueba = 10;
  COMPROBAR(tomarComando() == Evento::COMANDO_FINALIZAR);
  COMPROBAR(entregarComando(Evento::COMANDO_INICIAR));
  establecerConexionMqtt(false);
  establecerConexionMqtt(true);
  COMPROBAR(tomarComando() == Evento::NINGUNO);

  // Muestra A ya recibida; B tiene una pulsación aceptada, pero su entrega se
  // demora hasta después de finalizar. Se usan el debounce y el corte reales.
  const Evento alertas[] = {Evento::NINGUNO, Evento::ROPA_SECA, Evento::LLUVIA_DETECTADA};
  for (Evento alerta : alertas) {
    reiniciarPrueba();
    paso(0, 80, 80, ADC_SIN_LLUVIA, 0, Evento::COMANDO_INICIAR);
    ejecutarFSM(alerta);
    MensajeSensores a = {};
    a.lecturas = sensores;
    MensajeSensores b = a;
    botonPrueba = true;
    relojPrueba = 10;
    COMPROBAR(muestrearPulsador() == 0);
    relojPrueba = 50;
    b.pulsaciones = muestrearPulsador();
    COMPROBAR(b.pulsaciones == 1);
    procesarEntradas(&a, Evento::COMANDO_FINALIZAR);
    COMPROBAR(estadoActual == Estado::ESPERA && pulsacionesProcesadas == 1);
    procesarEntradas(&b, Evento::NINGUNO);
    COMPROBAR(estadoActual == Estado::ESPERA && numeroCiclo == 1);
    COMPROBAR(pwmPrueba == 0 && !tonoPrueba);
    relojPrueba = 1000;
    COMPROBAR(muestrearPulsador() == 1); // Mantener apretado no crea otro evento.
    procesarEntradas(&b, Evento::NINGUNO);
    COMPROBAR(estadoActual == Estado::ESPERA);
    procesarEntradas(&a, Evento::COMANDO_FINALIZAR); // A anterior al corte, en espera.
    COMPROBAR(pulsacionesProcesadas == 1 && estadoActual == Estado::ESPERA);

    botonPrueba = false;
    relojPrueba = 1010;
    muestrearPulsador();
    relojPrueba = 1050;
    muestrearPulsador();
    botonPrueba = true;
    relojPrueba = 1060;
    muestrearPulsador();
    relojPrueba = 1100;
    b.pulsaciones = muestrearPulsador();
    COMPROBAR(b.pulsaciones == 2);
    procesarEntradas(&b, Evento::NINGUNO);
    COMPROBAR(estadoActual == Estado::MONITOREANDO_SECADO && numeroCiclo == 2);
  }

  reiniciarPrueba();
  paso(0, 80, 80, ADC_SIN_LLUVIA, 0, Evento::COMANDO_INICIAR);
  MensajeSensores a = {};
  a.lecturas = sensores;
  botonPrueba = true;
  relojPrueba = 10;
  COMPROBAR(muestrearPulsador() == 0);
  // El productor acepta el flanco justo antes de que la FSM adquiera el corte.
  // Todavía no publicó B en la cola; aun así, el corte debe incluirlo.
  antesDeBloquear = aceptarPulsacionAntesDelCorte;
  procesarEntradas(&a, Evento::COMANDO_FINALIZAR);
  COMPROBAR(pulsacionesProcesadas == 1 && estadoActual == Estado::ESPERA);
  MensajeSensores b = a;
  b.pulsaciones = 1;
  procesarEntradas(&b, Evento::NINGUNO);
  COMPROBAR(estadoActual == Estado::ESPERA && numeroCiclo == 1);

  // El orden de contadores también debe funcionar al pasar UINT32_MAX -> 0.
  reiniciarPrueba();
  paso(0, 80, 80, ADC_SIN_LLUVIA, 0, Evento::COMANDO_INICIAR);
  a.lecturas = sensores;
  a.pulsaciones = UINT32_MAX;
  pulsacionesAceptadas = UINT32_MAX;
  pulsacionesProcesadas = UINT32_MAX;
  botonPrueba = true;
  relojPrueba = 10;
  muestrearPulsador();
  relojPrueba = 50;
  b = a;
  b.pulsaciones = muestrearPulsador();
  COMPROBAR(b.pulsaciones == 0);
  procesarEntradas(&a, Evento::COMANDO_FINALIZAR);
  COMPROBAR(pulsacionesProcesadas == 0 && estadoActual == Estado::ESPERA);
  procesarEntradas(&b, Evento::NINGUNO);
  procesarEntradas(&a, Evento::NINGUNO); // Una muestra vieja no hace retroceder el cursor.
  COMPROBAR(pulsacionesProcesadas == 0 && estadoActual == Estado::ESPERA);
  COMPROBAR(bloqueosPrueba == 0);
#undef COMPROBAR
  return 0;
}

int main()
{
  const int falla = probarFsm();
  if (falla != 0) {
    fprintf(stderr, "Falla en fsm.cpp:%d\n", falla);
    return 1;
  }
  puts("FSM real con dobles de GPIO/red/RTOS: OK");
  const int fallaIntercalados = probarIntercalados();
  if (fallaIntercalados != 0) {
    fprintf(stderr, "Falla de intercalados en fsm.cpp:%d\n", fallaIntercalados);
    return 1;
  }
  puts("Intercambio real y carreras con intercalados inyectados: OK");
  return 0;
}
