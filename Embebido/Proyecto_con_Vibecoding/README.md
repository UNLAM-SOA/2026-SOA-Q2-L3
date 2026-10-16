# Firmware IA — incremento 1: control local

Implementación nueva para Arduino-ESP32 y el circuito de [`../diagram.json`](../diagram.json), sin modificar la variante manual ni el esquema. **Todavía no incluye WiFi, MQTT ni Android; no completa V2.**

## Archivos y dependencias

- `Proyecto_con_Vibecoding.ino`: inicialización, dos tareas FreeRTOS, cola, FSM y actuadores.
- `Configuracion.h`: GPIO, calibración provisional, umbrales y tiempos.
- `ControlLocal.h`: cálculos, estabilidad, histéresis, antirrebote y plazo sonoro.
- `PruebasControl.h`: autopruebas sin GPIO ni esperas; se ejecutan antes de crear tareas. Una falla detiene el control con actuadores apagados e indica la línea.
- `pruebas/control_local.cpp`: ejecuta las mismas autopruebas en una computadora.

Se requiere el core **Arduino-ESP32** y sus componentes incluidos: Arduino y FreeRTOS. No se usan bibliotecas externas. `analogWrite()`, `tone()` y `noTone()` son las APIs Arduino utilizadas; la compatibilidad con la versión concreta del core debe comprobarse al compilar. No se fija una versión no verificada.

## Compilar y probar en Wokwi web

No hay integración automática con Wokwi en este entorno. Procedimiento para ejecución asistida:

1. Abrir un proyecto Arduino ESP32 en Wokwi, con ESP32 DevKit v1.
2. Pegar el contenido de `Proyecto_con_Vibecoding.ino` en `sketch.ino`.
3. Agregar `Configuracion.h`, `ControlLocal.h` y `PruebasControl.h` como archivos del proyecto, conservando los nombres.
4. Reemplazar el contenido del `diagram.json` **del proyecto de simulación** por el contenido exacto de `Embebido/diagram.json`. No editar el esquema del repositorio, conexiones, componentes ni GPIO. No agregar el ejecutable de pruebas a Wokwi.
5. Iniciar la simulación (▶): Wokwi compila el sketch antes de ejecutarlo. Registrar resultado de compilación y versión del core si está informada por el entorno.
6. Abrir el monitor serie a 115200. Debe aparecer `[TEST] Control local: OK` y el estado inicial `ESPERA`. Si falla una autoprueba, no se crean tareas.
7. Manipular los tres potenciómetros y el pulsador según la matriz siguiente. Usar las lecturas del monitor, no asumir que la posición del mando equivale exactamente al ADC deseado.

Este incremento no se conecta a ninguna red. `Wokwi-GUEST` queda para el incremento WiFi/MQTT. No hace falta `libraries.txt`, PlatformIO ni `wokwi.toml` para este procedimiento web.

### Entradas y valores provisionales

| Componente Wokwi | GPIO | Interpretación inicial |
| --- | --- | --- |
| `pot1` / `pot2` | 35 / 34 | ADC 0 = 100 % humedad; ADC 4095 = 0 % |
| `potRain` | 32 | ADC ≥ 2000 = lluvia; el rótulo gráfico no cambia su función |
| `btnCycle` | 26 | Pulsado = LOW; `INPUT_PULLUP` |
| LED / buzzer | 18 / 25 | PWM / tono de 1000 Hz con cadencia |

Los potenciómetros comienzan en 0 en el esquema: prendas húmedas, sin lluvia. Calibraciones por broche en `BROCHE_1` y `BROCHE_2`, con extremos `humedo` y `seco`; permiten invertir el sentido. Para lluvia, usar `UMBRAL_LLUVIA_ADC` y `LLUVIA_CON_ADC_ALTO`. Los extremos deben estar entre 0 y 4095 y ser distintos; se comprueban al compilar.

Humedad general = máximo de las dos humedades. Las divisiones enteras truncan hacia cero y luego se limita a 0–100. Progreso = `limitar((100 - H) * 100 / 80, 0, 100)` y PWM = `progreso * 255 / 100`, truncado. Ejemplo: H = 60 → progreso 50 → PWM 127. En ropa seca el LED vale 255 aunque la histéresis conserve ese estado con progreso instantáneo menor a 100.

## Estructura académica y concurrencia

- `setup()` crea cola y tareas; `loop()` permanece vacío.
- `tareaSensores()` adquiere entradas cada 50 ms y aplica antirrebote de 40 ms. Un flanco estable de pulsación incrementa un contador; mantener presionado no repite acciones. La liberación también debe estabilizarse antes de otra pulsación.
- Envía por valor la muestra completa y el contador acumulativo a una cola de longitud 1 mediante `xQueueOverwrite()`. No comparte una estructura mutable de lecturas ni se bloquea esperando espacio.
- `tareaFSM()` recibe la última muestra, convierte cada pulsación pendiente en `Evento::BOTON_CICLO` y luego procesa las condiciones actuales. Así no se pierde una pulsación aunque su muestra original haya sido sobrescrita, ni se forma una cola de decisiones ambientales antiguas.
- A diferencia de la referencia, la clasificación temporal de seco/lluvia y la generación de sus eventos se realizan **en la tarea FSM**, para que el inicio pueda reiniciar la clasificación sin acceso concurrente. Los eventos siguen siendo explícitos (`enum class Evento`) y `ejecutarFSM()` conserva el **switch de estado con switch de evento dentro de cada estado**. No se usa otra arquitectura de FSM.
- La FSM es la única dueña del estado, filtros del ciclo y actuadores después de iniciar las tareas. `cambiarEstado()` centraliza transiciones, efectos inmediatos y registros.
- Finalizar tiene prioridad sobre las condiciones de la muestra; lluvia confirmada tiene prioridad sobre seco/húmedo. Nuevo ciclo = clasificación húmeda inicial, sin heredar la anterior. Confirmar seco requiere 20 % o menos por 1000 ms; volver a húmedo requiere 25 % o más por 1000 ms. Lluvia y fin de lluvia también requieren 1000 ms.
- El buzzer se atiende aun cuando venza la espera de recepción. Alterna encendido/apagado cada 1200 ms (seco) o 300 ms (lluvia). Cada entrada a alerta inicia un plazo de 30 s, con silencio persistente al vencer, incluso tras un desborde posterior de `millis()`. Las repeticiones del mismo estado no reinician el plazo.
- Finalizar apaga actuadores al procesar el evento, sin esperar los 1000 ms de filtrado. No hay `delay()` ni espera activa: solo esperas de FreeRTOS. Con adquisición de 50 ms, la aceptación del pulsador queda cuantizada al muestreo; mantenerlo al menos 150 ms facilita una prueba manual reproducible.
- Las fallas de creación de cola/tareas dejan el control detenido con actuadores apagados. Si falla crear la FSM, se elimina la tarea de sensores antes de liberar la cola.

El filtrado estima estabilidad a partir de las muestras: no detecta cambios más rápidos que el muestreo. Este incremento no incorpora diagnóstico de sensores desconectados ni watchdog adicional.

## Pruebas de lógica en computadora

Desde este directorio, con `g++` disponible, colocar el ejecutable fuera del repositorio. En el entorno Windows actual se encontró `g++` en Ubuntu/WSL:

```powershell
wsl.exe -d Ubuntu --exec g++ -std=c++11 -Wall -Wextra -Werror -pedantic pruebas/control_local.cpp -o /mnt/c/Users/Mateo/AppData/Local/Temp/opencode/control_local_tests
wsl.exe -d Ubuntu --exec /mnt/c/Users/Mateo/AppData/Local/Temp/opencode/control_local_tests
```

La ruta de salida es específica de esta computadora; adaptarla en otro entorno. Resultado esperado: `Control local: OK`, salida 0. Para ejecutar también con sanitizadores, agregar `'-fsanitize=address,undefined' -fno-omit-frame-pointer` al comando de compilación (las comillas evitan que PowerShell interprete la coma). Las pruebas usan los umbrales, sentidos de lluvia y tiempos iniciales acordados; si se modifica esa configuración, revisar también sus expectativas.

Cobertura: extremos/rango/calibración invertida de humedad, progreso y PWM; histéresis 20/25, interrupción de estabilidad, reinicio del ciclo, confirmación y fin de lluvia, condiciones simultáneas, rebote/pulsación sostenida/liberación, cadencias, corte a 30 s, reentrada y desborde temporal. **No ejecutan tareas FreeRTOS, el switch de la FSM ni periféricos Arduino.**

## Matriz de aceptación en Wokwi (pendiente de ejecución)

Registrar para cada fila: condiciones, tiempos observados, log de transiciones, resultado y evidencia real. No completar filas por inspección del código.

| Caso | Acción y resultado esperado |
| --- | --- |
| Encendido | Con lluvia o prendas secas, permanece en espera con LED/buzzer apagados. |
| Inicio húmedo | Con ambas prendas húmedas y lluvia ADC < 2000, pulsar: monitoreo y LED proporcional. |
| Más húmeda | Una prenda a 0 % y otra a 60 %: general 60 %, progreso 50 %, PWM 127; no ropa seca. |
| Inicio seco | Ambas ≤ 20 %, sin lluvia: tras inicio y 1 s estable, ropa seca; LED máximo. |
| Histéresis | Desde seco, 21–24 % conserva seco; 25 % durante 1 s vuelve a monitoreo. Desde húmedo, 21–24 % no confirma seco. |
| Ruido de humedad | Bajar a ≤ 20 % menos de 1 s y volver a > 20 %: no confirma seco. Un nuevo descenso requiere 1 s completo. |
| Lluvia | Desde monitoreo o seco, ADC ≥ 2000 por 1 s: lluvia. Si ambos criterios se confirman juntos, gana lluvia. |
| Ruido de lluvia | Cambiar de lado del umbral menos de 1 s no cambia la condición confirmada; se prueba aparición y finalización. |
| Fin de lluvia | ADC < 2000 por 1 s: seco si ya fue confirmado; en caso contrario monitoreo. Humedad sigue clasificándose durante lluvia. |
| Pulsador | Finalizar desde cada estado activo apaga LED y sonido sin esperar al filtrado; sostenerlo no reinicia. Liberar antes de volver a pulsar. |
| Nuevo ciclo | Después de finalizar seco, iniciar con 22 %: monitoreo, sin heredar seco. |
| PWM | H = 100/60/20/0 → progreso 0/50/100/100. En monitoreo/lluvia PWM 0/127/255/255. |
| Cadencias | Seco: alternancia cada 1,2 s; lluvia: cada 0,3 s. Misma frecuencia de tono. |
| Plazo | Mantener una alerta más de 30 s: silencio, sin cambiar estado/LED. Continuar otros 5 s: no reanuda. |
| Reentrada | Cambiar de seco a lluvia, o salir y regresar a una alerta: nuevo plazo de 30 s. |
| Reinicio | Reiniciar ESP32 durante un ciclo: espera, actuadores apagados, nuevo inicio explícito. |

El pulsador del esquema tiene `bounce: "0"`: la simulación del pulsador no demuestra rechazo de rebote. Las autopruebas inyectan rebote temporal sintético sin cambiar el esquema. La matriz requiere aún verificar el PWM/sonido y el comportamiento real de la FSM/FreeRTOS.

## Registro de verificación de este incremento

Entorno comprobado el 15/10/2026: Windows, Ubuntu/WSL, `g++ (Ubuntu 15.2.0-16ubuntu1) 15.2.0`. Ejecutable generado fuera del repositorio.

| Verificación | Resultado |
| --- | --- |
| Compilación de autopruebas C++11 en Ubuntu/WSL (`g++`, advertencias como errores) | Ejecutada, sin errores |
| Ejecución de autopruebas en Ubuntu/WSL | Ejecutada: `Control local: OK` |
| Autopruebas con AddressSanitizer y UndefinedBehaviorSanitizer | Ejecutadas: `Control local: OK`, sin diagnósticos |
| Compilación del firmware con Arduino-ESP32 | **Pendiente**; compilar pruebas C++ no equivale a compilar firmware |
| Simulación con el esquema original en Wokwi | **Pendiente**, sin herramienta disponible para operar Wokwi desde esta sesión |
| MQTT / WiFi | No implementados ni probados en este incremento |
| Pruebas físicas | **Pendientes**, no inferidas de simulación ni autopruebas |

### Diferencias respecto de la referencia manual

Transporte coherente por valor y contador de pulsaciones en lugar de datos globales sin sincronizar; última muestra en vez de acumulación de eventos ambientales; clasificación y filtrado propiedad de la FSM; histéresis y estabilidad; corte sonoro de 30 s sin rearmado por repetición; verificación de creación de tareas y autopruebas. Se mantiene el patrón de estados/eventos, switch anidados y dos tareas, y se omite la red hasta el siguiente incremento.

## Antes del hardware

Confirmar placa, alimentación, conexiones y medir la calibración real de ambos broches y lluvia. El porcentaje es un índice estimado de conductividad, no humedad certificada. Un broche desconectado puede parecer ropa seca: mantener ambos colocados y finalizar el ciclo al retirar las prendas.

El buzzer Wokwi es pasivo; su uso con `tone()` **no valida el módulo físico**. Confirmar activo/pasivo, polaridad, tensión/corriente y necesidad de etapa de manejo antes de conectarlo. El rótulo “Buzzer (5V)” del dibujo no autoriza conectar una carga de 5 V directamente al GPIO. No aplicar más de la tensión admisible a las entradas del ESP32.
