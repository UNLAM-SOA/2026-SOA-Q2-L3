#include <stdio.h>

// Se ejecutan las funciones del firmware real, con GPIO/red/RTOS sustituidos.
#include "../Proyecto_con_Vibecoding.ino"

uint32_t relojPrueba = 0;
int pwmPrueba = 0;
bool tonoPrueba = false;
SerialPrueba Serial;
InstantaneaSistema instantaneaPrueba;

void compartirEstado(const InstantaneaSistema &actual) { instantaneaPrueba = actual; }
Evento tomarComando(uint32_t) { return Evento::NINGUNO; }
bool iniciarComunicacion() { return false; }

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
  instantaneaPrueba = InstantaneaSistema{};
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
  procesarEntradas(&muestra, comando);
}

int probarFsm()
{
#define COMPROBAR(condicion) do { if (!(condicion)) return __LINE__; } while (false)
  reiniciarPrueba();
  paso(0, 0, 0, 4095, 0);
  paso(2000, 0, 0, 4095, 0);
  COMPROBAR(estadoActual == Estado::ESPERA && pwmPrueba == 0 && !tonoPrueba);
  procesarEntradas(nullptr, Evento::COMANDO_FINALIZAR);
  COMPROBAR(revisionEstado == 0);
  procesarEntradas(nullptr, Evento::COMANDO_INICIAR);
  COMPROBAR(estadoActual == Estado::MONITOREANDO_SECADO && !lecturaValida && pwmPrueba == 0);
  COMPROBAR(!instantaneaPrueba.lecturaValida && numeroCiclo == 1);
  procesarEntradas(nullptr, Evento::COMANDO_INICIAR);
  COMPROBAR(revisionEstado == 1 && numeroCiclo == 1);
  paso(2050, 0, 60, 0, 0);
  paso(3050, 0, 60, 0, 0);
  COMPROBAR(estadoActual == Estado::MONITOREANDO_SECADO && pwmPrueba == 127);
  COMPROBAR(instantaneaPrueba.humedad == 60 && instantaneaPrueba.lecturaValida);

  reiniciarPrueba();
  paso(0, 20, 20, 2000, 0, Evento::COMANDO_INICIAR);
  paso(1000, 20, 20, 2000, 0);
  COMPROBAR(estadoActual == Estado::NOTIFICANDO_LLUVIA && tonoPrueba && pwmPrueba == 255);
  const uint32_t revisionAlerta = revisionEstado;
  paso(1400, 20, 20, 2000, 0, Evento::COMANDO_INICIAR);
  COMPROBAR(revisionEstado == revisionAlerta && numeroCiclo == 1);
  COMPROBAR(alertaSonora.entrada == 1000 && !tonoPrueba);
  relojPrueba = 31000;
  procesarEntradas(nullptr, Evento::COMANDO_INICIAR);
  COMPROBAR(!tonoPrueba && estadoActual == Estado::NOTIFICANDO_LLUVIA);
  paso(32000, 20, 20, 0, 0);
  paso(33000, 20, 20, 0, 0);
  COMPROBAR(estadoActual == Estado::NOTIFICANDO_ROPA_SECA && tonoPrueba);
  COMPROBAR(alertaSonora.entrada == 33000);
  paso(34000, 25, 20, 0, 0);
  paso(35000, 25, 20, 0, 0);
  COMPROBAR(estadoActual == Estado::MONITOREANDO_SECADO && !tonoPrueba);

  const Evento alertas[] = {Evento::NINGUNO, Evento::ROPA_SECA, Evento::LLUVIA_DETECTADA};
  for (Evento alerta : alertas) {
    reiniciarPrueba();
    paso(0, 60, 60, 0, 0, Evento::COMANDO_INICIAR);
    ejecutarFSM(alerta);
    const Estado anterior = estadoActual;
    const uint32_t revision = revisionEstado;
    relojPrueba = 500;
    procesarEntradas(nullptr, Evento::COMANDO_INICIAR);
    COMPROBAR(estadoActual == anterior && revisionEstado == revision && numeroCiclo == 1);
    procesarEntradas(nullptr, Evento::COMANDO_FINALIZAR);
    COMPROBAR(estadoActual == Estado::ESPERA && pwmPrueba == 0 && !tonoPrueba);
    COMPROBAR(!instantaneaPrueba.lecturaValida);
    const uint32_t finalizada = revisionEstado;
    procesarEntradas(nullptr, Evento::COMANDO_FINALIZAR);
    COMPROBAR(revisionEstado == finalizada);
  }

  reiniciarPrueba();
  paso(0, 80, 80, 0, 1);
  COMPROBAR(estadoActual == Estado::MONITOREANDO_SECADO);
  paso(50, 80, 80, 0, 2, Evento::COMANDO_INICIAR);
  COMPROBAR(estadoActual == Estado::ESPERA); // Finalización local gana al inicio remoto.
  paso(100, 80, 80, 0, 3, Evento::COMANDO_FINALIZAR);
  COMPROBAR(estadoActual == Estado::ESPERA && pulsacionesProcesadas == 3);
  paso(150, 80, 80, 0, 3);
  COMPROBAR(estadoActual == Estado::ESPERA); // No reaparece la pulsación descartada.
  paso(200, 80, 80, 0, 4);
  paso(250, 80, 80, 0, 5, Evento::COMANDO_FINALIZAR);
  COMPROBAR(estadoActual == Estado::ESPERA && pwmPrueba == 0 && !tonoPrueba);

  reiniciarPrueba();
  paso(0, 20, 20, 0, 0, Evento::COMANDO_INICIAR);
  paso(1000, 20, 20, 0, 0);
  COMPROBAR(estadoActual == Estado::NOTIFICANDO_ROPA_SECA);
  procesarEntradas(nullptr, Evento::COMANDO_FINALIZAR);
  paso(1100, 22, 22, 0, 0, Evento::COMANDO_INICIAR);
  paso(2200, 22, 22, 0, 0);
  COMPROBAR(estadoActual == Estado::MONITOREANDO_SECADO && !condiciones.seca.valor);
  COMPROBAR(numeroCiclo == 2);
  paso(2250, 22, 22, 0, 2);
  COMPROBAR(estadoActual == Estado::MONITOREANDO_SECADO && numeroCiclo == 3);
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
  return 0;
}
