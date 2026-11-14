#pragma once

#include "Configuracion.h"

// Funciones independientes de Arduino para poder probar la lógica sin hardware.
inline int limitar(int valor, int minimo, int maximo)
{
  return valor < minimo ? minimo : (valor > maximo ? maximo : valor);
}

inline int calcularHumedad(int adc, Configuracion::CalibracionBroche calibracion)
{
  adc = limitar(adc, 0, Configuracion::ADC_MAXIMO);
  const int humedad = (adc - calibracion.seco) * 100 /
                      (calibracion.humedo - calibracion.seco);
  return limitar(humedad, 0, 100);
}

inline int calcularProgreso(int humedad)
{
  return limitar((100 - limitar(humedad, 0, 100)) * 100 /
                 (100 - Configuracion::UMBRAL_SECO_PCT), 0, 100);
}

inline int calcularPwm(int progreso)
{
  return limitar(progreso, 0, 100) * Configuracion::PWM_MAXIMO / 100;
}

// Las restas sin signo toleran el desborde de millis().
inline bool transcurrio(uint32_t ahora, uint32_t inicio, uint32_t plazo)
{
  return uint32_t(ahora - inicio) >= plazo;
}

inline bool contadorPosterior(uint32_t candidato, uint32_t referencia)
{
  // shortcut: requiere menos de 2^31 pulsaciones pendientes; ampliar el contador si se supera.
  const uint32_t diferencia = candidato - referencia;
  return diferencia != 0 && diferencia < (uint32_t(1) << 31);
}

inline bool detectarLluvia(int adc, int umbral, bool adcAlto)
{
  return adcAlto ? adc >= umbral : adc <= umbral;
}

struct CondicionEstable {
  bool valor = false;
  bool candidato = false;
  uint32_t desde = 0;

  void reiniciar(uint32_t ahora)
  {
    valor = false;
    candidato = false;
    desde = ahora;
  }

  bool actualizar(bool lectura, uint32_t ahora)
  {
    if (lectura == valor) {
      candidato = valor;
      desde = ahora;
    } else if (lectura != candidato) {
      candidato = lectura;
      desde = ahora;
    } else if (transcurrio(ahora, desde, Configuracion::ESTABILIDAD_MS)) {
      valor = candidato;
    }
    return valor;
  }
};

struct CondicionesCiclo {
  CondicionEstable seca;
  CondicionEstable lluvia;

  void reiniciar(uint32_t ahora)
  {
    seca.reiniciar(ahora);
    lluvia.reiniciar(ahora);
  }

  void actualizar(int humedad, int adcLluvia, uint32_t ahora)
  {
    const bool criterioSeco = seca.valor
      ? humedad < Configuracion::UMBRAL_HUMEDO_PCT
      : humedad <= Configuracion::UMBRAL_SECO_PCT;
    seca.actualizar(criterioSeco, ahora);
    const bool criterioLluvia = detectarLluvia(adcLluvia,
      Configuracion::UMBRAL_LLUVIA_ADC, Configuracion::LLUVIA_CON_ADC_ALTO);
    lluvia.actualizar(criterioLluvia, ahora);
  }
};

struct BotonAntirrebote {
  bool ultimaLectura = false;
  bool estable = false;
  uint32_t desde = 0;

  // true una sola vez por flanco de pulsación estable, no por liberación.
  bool actualizar(bool presionado, uint32_t ahora)
  {
    if (presionado != ultimaLectura) {
      ultimaLectura = presionado;
      desde = ahora;
    }
    if (presionado == estable ||
        !transcurrio(ahora, desde, Configuracion::ANTIRREBOTE_MS)) {
      return false;
    }
    estable = presionado;
    return estable;
  }
};

struct AlertaSonora {
  uint32_t entrada = 0;
  bool agotada = true;

  void iniciar(uint32_t ahora)
  {
    entrada = ahora;
    agotada = false;
  }

  bool actualizar(uint32_t ahora, uint32_t cadencia)
  {
    const uint32_t tiempo = ahora - entrada;
    if (tiempo >= Configuracion::MAXIMO_ALERTA_MS) {
      agotada = true;
    }
    // Una alerta agotada no revive al volver a desbordar millis().
    return !agotada && (tiempo / cadencia) % 2 == 0;
  }
};
