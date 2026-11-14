#pragma once

#include <stdint.h>

// Solo para pruebas de FSM en computadora: no simula periféricos ni tiempos RTOS.
#ifdef ARDUINO
#error "No incluir dobles de prueba en el firmware"
#endif

extern uint32_t relojPrueba;
extern int pwmPrueba;
extern bool tonoPrueba;
extern bool botonPrueba;

constexpr int INPUT = 0;
constexpr int OUTPUT = 1;
constexpr int INPUT_PULLUP = 2;
constexpr int LOW = 0;

struct SerialPrueba {
  void begin(int) {}
  void println(const char *) {}
  template <typename... Args> void printf(const char *, Args...) {}
};
extern SerialPrueba Serial;

inline uint32_t millis() { return relojPrueba; }
inline void pinMode(int, int) {}
inline void analogReadResolution(int) {}
inline int analogRead(int) { return 0; }
inline int digitalRead(int) { return botonPrueba ? LOW : 1; }
inline void analogWrite(int, int valor) { pwmPrueba = valor; }
inline void tone(int, unsigned int) { tonoPrueba = true; }
inline void noTone(int) { tonoPrueba = false; }
