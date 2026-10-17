#pragma once

#include "ControlLocal.h"

// Devuelve la línea de la primera falla o 0. No toca GPIO ni espera tiempo real.
inline int probarControlLocal()
{
#define COMPROBAR(condicion) do { if (!(condicion)) return __LINE__; } while (false)
  using namespace Configuracion;
  COMPROBAR(calcularHumedad(BROCHE_1.humedo, BROCHE_1) == 100);
  COMPROBAR(calcularHumedad(BROCHE_1.seco, BROCHE_1) == 0);
  COMPROBAR(calcularHumedad(BROCHE_2.humedo, BROCHE_2) == 100);
  COMPROBAR(calcularHumedad(BROCHE_2.seco, BROCHE_2) == 0);
  const CalibracionBroche referencia = {0, ADC_MAXIMO};
  COMPROBAR(calcularHumedad(-1, referencia) == 100);
  COMPROBAR(calcularHumedad(5000, referencia) == 0);
  const CalibracionBroche invertida = {ADC_MAXIMO, 0};
  COMPROBAR(calcularHumedad(ADC_MAXIMO, invertida) == 100);
  COMPROBAR(calcularHumedad(0, invertida) == 0);
  const CalibracionBroche acotada = {1000, 3000};
  COMPROBAR(calcularHumedad(2000, acotada) == 50);
  COMPROBAR(calcularHumedad(0, acotada) == 100);
  COMPROBAR(calcularHumedad(4095, acotada) == 0);
  COMPROBAR(calcularProgreso(100) == 0);
  COMPROBAR(calcularProgreso(60) == 50);
  COMPROBAR(calcularProgreso(20) == 100);
  COMPROBAR(calcularProgreso(0) == 100);
  COMPROBAR(calcularProgreso(59) == 51);
  COMPROBAR(calcularPwm(50) == 127);
  COMPROBAR(calcularPwm(100) == 255);
  COMPROBAR(contadorPosterior(1, 0));
  COMPROBAR(!contadorPosterior(0, 1));
  COMPROBAR(!contadorPosterior(1, 1));
  COMPROBAR(contadorPosterior(0, UINT32_MAX));
  COMPROBAR(!contadorPosterior(UINT32_MAX, 0));

  CondicionesCiclo c;
  c.reiniciar(0);
  c.actualizar(20, 0, 0);
  c.actualizar(20, 0, 999);
  COMPROBAR(!c.seca.valor);
  c.actualizar(20, 0, 1000);
  COMPROBAR(c.seca.valor);
  c.actualizar(24, 0, 2000);
  COMPROBAR(c.seca.valor);
  c.actualizar(25, 0, 2100);
  c.actualizar(25, 0, 3099);
  COMPROBAR(c.seca.valor);
  c.actualizar(25, 0, 3100);
  COMPROBAR(!c.seca.valor);
  c.actualizar(20, 0, 3200);
  c.actualizar(21, 0, 4100);
  c.actualizar(20, 0, 4200);
  c.actualizar(20, 0, 5199);
  COMPROBAR(!c.seca.valor);
  c.actualizar(20, 0, 5200);
  COMPROBAR(c.seca.valor);
  c.reiniciar(5300);
  c.actualizar(22, 0, 6300);
  COMPROBAR(!c.seca.valor);

  // Fixtures explícitos: probar ambos sentidos no depende de la calibración
  // elegida para el equipo, ni obliga a editar estas expectativas al invertirla.
  const bool sentidos[] = {true, false};
  for (bool adcAlto : sentidos) {
    const int umbralPrueba = 2000;
    const int adcSinLluvia = adcAlto ? 1999 : 2001;
    const int adcConLluvia = adcAlto ? 2001 : 1999;
    COMPROBAR(detectarLluvia(umbralPrueba, umbralPrueba, adcAlto));
    COMPROBAR(detectarLluvia(adcConLluvia, umbralPrueba, adcAlto));
    COMPROBAR(!detectarLluvia(adcSinLluvia, umbralPrueba, adcAlto));
    CondicionEstable lluvia;
    lluvia.reiniciar(0);
    lluvia.actualizar(detectarLluvia(adcConLluvia, umbralPrueba, adcAlto), 0);
    lluvia.actualizar(detectarLluvia(adcSinLluvia, umbralPrueba, adcAlto), 900);
    lluvia.actualizar(detectarLluvia(umbralPrueba, umbralPrueba, adcAlto), 1000);
    COMPROBAR(!lluvia.actualizar(true, 1999));
    COMPROBAR(lluvia.actualizar(true, 2000));
    lluvia.actualizar(detectarLluvia(adcSinLluvia, umbralPrueba, adcAlto), 2100);
    COMPROBAR(lluvia.actualizar(false, 3099));
    COMPROBAR(!lluvia.actualizar(false, 3100));
  }

  c.reiniciar(0);
  c.actualizar(20, UMBRAL_LLUVIA_ADC, 0);
  c.actualizar(20, UMBRAL_LLUVIA_ADC, 1000);
  COMPROBAR(c.seca.valor && c.lluvia.valor);
  c.actualizar(25, UMBRAL_LLUVIA_ADC, 1100);
  c.actualizar(25, UMBRAL_LLUVIA_ADC, 2100);
  COMPROBAR(!c.seca.valor && c.lluvia.valor);

  BotonAntirrebote boton;
  COMPROBAR(!boton.actualizar(true, 0));
  COMPROBAR(!boton.actualizar(false, 10));
  COMPROBAR(!boton.actualizar(true, 20));
  COMPROBAR(!boton.actualizar(true, 59));
  COMPROBAR(boton.actualizar(true, 60));
  COMPROBAR(!boton.actualizar(true, 5000));
  COMPROBAR(!boton.actualizar(false, 5100));
  COMPROBAR(!boton.actualizar(false, 5140));
  COMPROBAR(!boton.actualizar(true, 5200));
  COMPROBAR(boton.actualizar(true, 5240));

  AlertaSonora alerta;
  COMPROBAR(!alerta.actualizar(0, CADENCIA_SECO_MS));
  alerta.iniciar(0);
  COMPROBAR(alerta.actualizar(0, CADENCIA_SECO_MS));
  COMPROBAR(!alerta.actualizar(1200, CADENCIA_SECO_MS));
  COMPROBAR(alerta.actualizar(2400, CADENCIA_SECO_MS));
  alerta.iniciar(0);
  COMPROBAR(!alerta.actualizar(300, CADENCIA_LLUVIA_MS));
  COMPROBAR(alerta.actualizar(600, CADENCIA_LLUVIA_MS));
  COMPROBAR(!alerta.actualizar(29999, CADENCIA_LLUVIA_MS));
  COMPROBAR(!alerta.actualizar(30000, CADENCIA_LLUVIA_MS));
  COMPROBAR(!alerta.actualizar(31200, CADENCIA_SECO_MS));
  COMPROBAR(!alerta.actualizar(0, CADENCIA_SECO_MS));
  alerta.iniciar(31000);
  COMPROBAR(alerta.actualizar(31000, CADENCIA_SECO_MS));
  COMPROBAR(transcurrio(24, UINT32_MAX - 25, 50));
  COMPROBAR(!transcurrio(23, UINT32_MAX - 25, 50));
  alerta.iniciar(UINT32_MAX - 25);
  COMPROBAR(alerta.actualizar(24, CADENCIA_LLUVIA_MS));
  CondicionEstable desborde;
  desborde.reiniciar(UINT32_MAX - 500);
  desborde.actualizar(true, UINT32_MAX - 500);
  COMPROBAR(!desborde.actualizar(true, 498));
  COMPROBAR(desborde.actualizar(true, 499));
#undef COMPROBAR
  return 0;
}
