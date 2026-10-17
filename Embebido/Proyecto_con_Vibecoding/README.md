# Firmware IA — incremento 2: control local + WiFi/MQTT

Implementación nueva para Arduino-ESP32 y el circuito de [`../diagram.json`](../diagram.json), sin modificar la variante manual ni el esquema. El usuario confirmó compilación, autopruebas, intercambio MQTT bidireccional, cliente Android externo y los 16 casos locales del incremento 2 anterior a las correcciones. **Matriz histórica I2: 31/33 aprobados. Después de las correcciones, el usuario reportó una regresión parcial satisfactoria en Wokwi el 17/10/2026: compilación ESP32, arranque, control local, pulsador y MQTT bidireccional. No equivale a repetir ni aprobar toda la matriz. Siguen pendientes caída WiFi durante ciclo, simultaneidad manual exacta y validación física. No incluye app Android propia ni completa V2.**

## Archivos y dependencias

- `Proyecto_con_Vibecoding.ino`: inicialización, tareas de sensores/FSM, cola y actuadores.
- `Comunicacion.cpp/.h`: tercera tarea FreeRTOS y WiFi/MQTT.
- `Intercambio.cpp/.h`: estado, buzón, reloj y aceptación/corte de pulsaciones bajo el mismo bloqueo, independiente del cliente de red.
- `ProtocoloMqtt.h`: estados/eventos, validación, buzón de comandos y programación de publicaciones, sin dependencia de Arduino.
- `Configuracion.h`: GPIO, calibración provisional, umbrales, tiempos y broker/topics.
- `ConfiguracionWifi.ejemplo.h`: red pública de simulación, sin secretos. El archivo local opcional `ConfiguracionWifi.h` está ignorado por Git.
- `ControlLocal.h`: cálculos, estabilidad, histéresis, antirrebote y plazo sonoro.
- `PruebasControl.h` / `PruebasMqtt.h`: autopruebas sin GPIO ni esperas, antes de crear tareas. Una falla detiene el control con actuadores apagados e indica la línea.
- `pruebas/control_local.cpp`: ejecuta esas autopruebas en una computadora.
- `pruebas/fsm.cpp` y `pruebas/dobles/`: ejecutan la FSM y el intercambio reales con reloj, GPIO y bloqueo sustituidos, sin scheduler ni red.
- `pruebas/verificar.ps1`: genera copias temporales con ambas polaridades de lluvia y ejecuta las suites en Ubuntu/WSL, sin modificar la configuración original ni copiar credenciales locales.

Se requiere el core **Arduino-ESP32**, sus componentes incluidos Arduino/FreeRTOS/WiFi, y **PubSubClient 2.8**, fijada en `libraries.txt`. `analogWrite()`, `tone()` y `noTone()` son las APIs de actuadores. La preconexión TCP con timeout explícito usa la firma disponible en core 2.0.17 y 3.3.0 (comprobada en sus headers publicados); **eso no sustituye una compilación ESP32**. No se fija una versión del core no probada. Registrar la que utilice Wokwi al compilar.

## Compilar y probar en Wokwi web

No hay integración automática con Wokwi en este entorno. Procedimiento para ejecución asistida:

1. Abrir un proyecto Arduino ESP32 en Wokwi, con ESP32 DevKit v1.
2. Pegar el contenido de `Proyecto_con_Vibecoding.ino` en `sketch.ino`.
3. Agregar los `.h` versionados, `Comunicacion.cpp` e **`Intercambio.cpp`** de este directorio, conservando los nombres. Si se actualiza el proyecto anterior, agregar también **`Intercambio.h`**. Incluir `ConfiguracionWifi.ejemplo.h`; no subir credenciales reales ni el directorio `pruebas/`. Agregar también `libraries.txt`, con `PubSubClient@2.8`.
4. Reemplazar el contenido del `diagram.json` **del proyecto de simulación** por el contenido exacto de `Embebido/diagram.json`. No editar el esquema del repositorio, conexiones, componentes ni GPIO. No agregar el ejecutable de pruebas a Wokwi.
5. Iniciar la simulación (▶): Wokwi compila el sketch antes de ejecutarlo. Registrar resultado de compilación y versión del core si está informada por el entorno.
6. Abrir el monitor serie a 115200. Deben aparecer `[TEST] Control local: OK`, `[TEST] Protocolo MQTT: OK` y el estado inicial `ESPERA`. Luego los intentos WiFi/MQTT. Si falla una autoprueba, no se crean tareas.
7. Manipular los tres potenciómetros y el pulsador según la matriz siguiente. Usar las lecturas del monitor, no asumir que la posición del mando equivale exactamente al ADC deseado.

Sin archivo local se utiliza `Wokwi-GUEST`, contraseña vacía. Para hardware, copiar `ConfiguracionWifi.ejemplo.h` como `ConfiguracionWifi.h` y editar solo la copia local. No versionarla ni cargarla al proyecto público de Wokwi. No hacen falta PlatformIO ni `wokwi.toml` para este procedimiento web.

### Entradas y valores provisionales

| Componente Wokwi | GPIO | Interpretación inicial |
| --- | --- | --- |
| `pot1` / `pot2` | 35 / 34 | ADC 0 = 100 % humedad; ADC 4095 = 0 % |
| `potRain` | 32 | ADC ≥ 2000 = lluvia; el rótulo gráfico no cambia su función |
| `btnCycle` | 26 | Pulsado = LOW; `INPUT_PULLUP` |
| LED / buzzer | 18 / 25 | PWM / tono de 1000 Hz con cadencia |

Los potenciómetros comienzan en 0 en el esquema: prendas húmedas, sin lluvia. Calibraciones por broche en `BROCHE_1` y `BROCHE_2`, con extremos `humedo` y `seco`; permiten invertir el sentido. Para lluvia, usar `UMBRAL_LLUVIA_ADC` y `LLUVIA_CON_ADC_ALTO`. Los extremos deben estar entre 0 y 4095 y ser distintos; se comprueban al compilar.

Se conserva expresamente la conversión del firmware manual por decisión del usuario. El potenciómetro representa la tensión de salida del circuito, no la conductividad de la tela. El montaje físico podría requerir invertir los extremos: `{ADC_MAXIMO, 0}` en cada broche representaría ADC alto = húmedo. No invertirlos durante la regresión actual; verificar primero el montaje y las mediciones reales.

Humedad general = máximo de las dos humedades. Las divisiones enteras truncan hacia cero y luego se limita a 0–100. Progreso = `limitar((100 - H) * 100 / 80, 0, 100)` y PWM = `progreso * 255 / 100`, truncado. Ejemplo: H = 60 → progreso 50 → PWM 127. En ropa seca el LED vale 255 aunque la histéresis conserve ese estado con progreso instantáneo menor a 100.

## Estructura académica y concurrencia

- `setup()` crea cola y tres tareas; `loop()` permanece vacío. Sensores y FSM tienen prioridad 2; comunicación, prioridad 1.
- `tareaSensores()` adquiere entradas cada 50 ms. `muestrearPulsador()` lee GPIO 26, aplica antirrebote de 40 ms e incrementa el contador de pulsaciones aceptadas bajo una misma sección crítica breve. Mantener presionado no repite acciones; la liberación también debe estabilizarse antes de otra pulsación.
- Envía por valor la muestra completa y el contador acumulativo a una cola de longitud 1 mediante `xQueueOverwrite()`. No comparte una estructura mutable de lecturas ni se bloquea esperando espacio.
- `tareaFSM()` recibe la última muestra y convierte las pulsaciones nuevas en `Evento::BOTON_CICLO`. Al finalizar un ciclo, el cursor se adelanta al último contador aceptado por sensores, aun si su muestra todavía no fue publicada en la cola. Las muestras atrasadas conservan sus lecturas pero no revierten el cursor ni reinician el ciclo. Se usa orden serial modular, con el límite de menos de 2^31 pulsaciones pendientes. Las anteriores al corte se cancelan deliberadamente, no se reinterpretan como nuevos inicios.
- A diferencia de la referencia, la clasificación temporal de seco/lluvia y la generación de sus eventos se realizan **en la tarea FSM**, para que el inicio pueda reiniciar la clasificación sin acceso concurrente. Los eventos siguen siendo explícitos (`enum class Evento`) y `ejecutarFSM()` conserva el **switch de estado con switch de evento dentro de cada estado**. No se usa otra arquitectura de FSM.
- La FSM es la única dueña del estado, filtros del ciclo y actuadores después de iniciar las tareas. `cambiarEstado()` centraliza transiciones, efectos inmediatos y registros.
- Solo la tarea de comunicación utiliza WiFi, el socket y `PubSubClient`. La FSM intercambia instantáneas y comandos bajo una sección crítica breve: no se hacen llamadas de red, registros ni esperas de FreeRTOS dentro de ella. La tercera tarea aísla las esperas de red; por sí sola no constituye una garantía absoluta contra deadlocks.
- El buzón remoto tiene capacidad para una orden pendiente. Repeticiones no se acumulan ni rejuvenecen el plazo; `FINALIZAR` reemplaza un `INICIAR` pendiente, nunca al revés. Recepción y consumo capturan `millis()` **después de adquirir el bloqueo**: no se compara un comando nuevo con un reloj tomado antes de su recepción. Se descartan órdenes al detectar desconexión y al cumplir 1000 ms sin consumo. No hay persistencia ni reproducción tras reconexión.
- `INICIAR`/`FINALIZAR` son eventos explícitos en los switch existentes. Iniciar un ciclo activo y finalizar en espera no cambian estado, filtros ni plazo sonoro. Toda finalización local/remota corta las pulsaciones ya aceptadas; `FINALIZAR` en espera también las cancela. El punto de orden es el corte bajo bloqueo: una pulsación aceptada después es nueva y puede iniciar otro ciclo, aunque la actualización visual se complete unas instrucciones después. Una finalización local también gana a `INICIAR` remoto de la misma iteración.
- Finalizar tiene prioridad sobre las condiciones de la muestra; lluvia confirmada tiene prioridad sobre seco/húmedo. Nuevo ciclo = clasificación húmeda inicial, sin heredar la anterior. Confirmar seco requiere 20 % o menos por 1000 ms; volver a húmedo requiere 25 % o más por 1000 ms. Lluvia y fin de lluvia también requieren 1000 ms.
- El buzzer se atiende aun cuando venza la espera de recepción. Alterna encendido/apagado cada 1200 ms (seco) o 300 ms (lluvia). Cada entrada a alerta inicia un plazo de 30 s, con silencio persistente al vencer, incluso tras un desborde posterior de `millis()`. Las repeticiones del mismo estado no reinician el plazo.
- Finalizar apaga actuadores al procesar el evento, sin esperar los 1000 ms de filtrado. No hay `delay()` ni espera activa: solo esperas de FreeRTOS. Con adquisición de 50 ms, la aceptación del pulsador queda cuantizada al muestreo; mantenerlo al menos 150 ms facilita una prueba manual reproducible.
- Las fallas de creación de cola/tareas locales dejan el control detenido con actuadores apagados. Si falla crear la FSM, se elimina la tarea de sensores antes de liberar la cola.
- Si falla crear la tarea de comunicación o reservar su buffer MQTT, se registra el error y continúa el control local. No se apagan actuadores desde la tarea de red.

El filtrado estima estabilidad a partir de las muestras: no detecta cambios más rápidos que el muestreo. No se incorpora diagnóstico de sensores desconectados ni watchdog adicional.

## MQTT y recuperación de conexión

Broker inicial: `broker.emqx.io:1883`, TCP sin autenticación ni cifrado. Topics configurables en `Configuracion.h`; modificar `LAVADERO_PREFIJO_MQTT` cambia los tres. El cliente ESP32 usa `lavadero-ia-` seguido del identificador MAC de la placa, impreso por terminal. No reutilizar ese clientId en otros clientes salvo la prueba deliberada de desconexión.

| Topic | Dirección | Contenido |
| --- | --- | --- |
| `unlam/soa/grupo3/lavadero-ia/comando` | Recibe ESP32 | Exactamente `INICIAR` o `FINALIZAR` |
| `unlam/soa/grupo3/lavadero-ia/estado` | Publica ESP32 | `ESPERA`, `MONITOREANDO_SECADO`, `ROPA_SECA`, `LLUVIA` |
| `unlam/soa/grupo3/lavadero-ia/sensor` | Publica ESP32 | Humedad general, entero 0–100 sin `%` |

- Publicaciones QoS 0, `retain=false`; suscripción QoS 0 y sesión limpia. No hay confirmación explícita por comando: comprobar el **estado observado**, no el mensaje de envío o el log de recepción. Un comando idempotente puede confirmarse con el siguiente estado periódico.
- Estado al conectar/reconectar, al cambiar (la tarea de red toma la situación vigente) y cada 5 s incluso en espera. No se encolan estados históricos durante una caída.
- Humedad solo durante ciclo activo, al tener lectura válida al iniciar/reconectar y luego cada 5 s. Cada nuevo ciclo invalida la lectura anterior hasta recibir una muestra; en espera no se publica humedad. La captura del estado y la humedad está sincronizada, sin lecturas parciales entre tareas.
- Validación por topic exacto, longitud y bytes exactos del comando; se rechazan vacíos, espacios, saltos de línea, minúsculas, nulos incrustados y mensajes desconocidos. No se construye un `String` con datos recibidos.
- Reintento WiFi y MQTT cada 5 s; no hay bucles propios de espera de conexión. El intento TCP usa 1500 ms y la espera MQTT 1 s, con keepalive de 5 s. **No son un límite total de toda operación:** DNS, escrituras y recepción de bytes sucesivos pueden esperar aparte dentro de las bibliotecas. Por eso se ejecutan en la tarea de menor prioridad, sin mantener el bloqueo compartido.
- Una desconexión puede tardar en detectarse; se vacía el buzón cuando se detecta, no en un instante de pérdida física que el firmware aún desconoce. Reconectar conserva ciclo y plazo sonoro; reiniciar vuelve a espera.
- `subscribe()` exitoso indica envío de la suscripción, no confirmación de autorización del broker; la prueba real requiere recibir un comando. `publish()` exitoso tampoco garantiza recepción por Android (QoS 0).

**Seguridad y comandos retenidos:** es un broker público: terceros pueden leer/publicar en los topics. No enviar datos sensibles. Los emisores deben tener **Retain desactivado y no guardar órdenes para reenviarlas al reconectar**. PubSubClient 2.8 no expone el indicador RETAIN al callback: no se afirma que el receptor distinga un comando retenido accidental de uno nuevo. Una sesión limpia no elimina retenidos. Si algún cliente dejó una orden retenida, eliminarla en el broker desde ese cliente antes de iniciar/reconectar el ESP32. No se modificó la biblioteca ni se agregó una falsa garantía de autenticidad.

### Cliente externo para las pruebas

1. Conectar un cliente MQTT a `broker.emqx.io`, puerto 1883, TCP, sin credenciales, clientId propio y sesión limpia. No activar colas de envío offline ni Retain.
2. Suscribirse con QoS 0 a `unlam/soa/grupo3/lavadero-ia/estado` y `unlam/soa/grupo3/lavadero-ia/sensor` (o al comodín `unlam/soa/grupo3/lavadero-ia/#`). Con comodín también se verán las órdenes propias: no confundirlas con respuestas del ESP32.
3. Esperar `ESPERA`. Publicar `INICIAR` en `.../comando` con QoS 0 y Retain desactivado; observar estado activo y humedad. Publicar `FINALIZAR`; observar `ESPERA` y actuadores apagados.
4. Completar los casos nuevos y repetir los 16 locales. Para la conectividad académica V2, repetir también con una app cliente descargada de Play Store en el teléfono y registrar nombre/versión. No se da esa prueba por realizada con un cliente de escritorio.

Si ya está instalado Mosquitto, los comandos equivalentes son (cada línea de suscripción deja el proceso abierto):

```powershell
mosquitto_sub -h broker.emqx.io -p 1883 -q 0 -t 'unlam/soa/grupo3/lavadero-ia/#' -v
mosquitto_pub -h broker.emqx.io -p 1883 -q 0 -t 'unlam/soa/grupo3/lavadero-ia/comando' -m INICIAR
mosquitto_pub -h broker.emqx.io -p 1883 -q 0 -t 'unlam/soa/grupo3/lavadero-ia/comando' -m FINALIZAR
```

No usar `-r` (retención) ni `-c` (sesión persistente). Son ejemplos: no se instaló ni ejecutó Mosquitto en esta sesión.

Para forzar una caída MQTT sin tocar el broker público, conectar **una sola vez** otro cliente con el clientId del propio ESP32: el broker desconectará la sesión anterior. Desactivar la reconexión automática de ese cliente y cerrarlo para permitir la recuperación del ESP32. No utilizar identificadores ajenos. Para caída WiFi inicial, usar temporalmente un SSID inexistente en `ConfiguracionWifi.h` y recompilar; luego restaurarlo. Una caída WiFi durante un ciclo necesita un mecanismo real de interrupción de red; si el entorno Wokwi disponible no lo permite, registrar ese caso pendiente y probarlo con hardware o un entorno controlable. Detener toda la simulación no equivale a cortar solo WiFi.

## Pruebas de lógica en computadora

Procedimiento recomendado desde este directorio (requiere Ubuntu/WSL con `g++`):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\pruebas\verificar.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\pruebas\verificar.ps1 -Sanitizadores
```

La política de ejecución se aplica solo a ese proceso; no se cambia la configuración permanente de PowerShell. Cada ejecución compila ambas suites con `LLUVIA_CON_ADC_ALTO=true` y `false` en copias generadas bajo `%LOCALAPPDATA%\Temp\opencode\`. La configuración del repositorio sigue en `true`. El script informa la ruta de artefactos y falla si una compilación o suite falla. No ejecuta Wokwi, FreeRTOS real ni llamadas de red.

Desde este directorio, con `g++` disponible, colocar el ejecutable fuera del repositorio. En el entorno Windows actual se encontró `g++` en Ubuntu/WSL:

```powershell
wsl.exe -d Ubuntu --exec g++ -std=c++11 -Wall -Wextra -Werror -pedantic pruebas/control_local.cpp -o /mnt/c/Users/Mateo/AppData/Local/Temp/opencode/control_local_tests
wsl.exe -d Ubuntu --exec /mnt/c/Users/Mateo/AppData/Local/Temp/opencode/control_local_tests
wsl.exe -d Ubuntu --exec g++ -std=c++11 -Wall -Wextra -Werror -pedantic -I pruebas/dobles pruebas/fsm.cpp -o /mnt/c/Users/Mateo/AppData/Local/Temp/opencode/fsm_mqtt_tests
wsl.exe -d Ubuntu --exec /mnt/c/Users/Mateo/AppData/Local/Temp/opencode/fsm_mqtt_tests
```

La ruta de salida es específica de esta computadora; adaptarla en otro entorno. Resultados esperados: `Control local: OK`, `Protocolo MQTT: OK`, `FSM real con dobles de GPIO/red/RTOS: OK` e `Intercambio real y carreras con intercalados inyectados: OK`, salida 0. Para ejecutar también con sanitizadores, agregar `'-fsanitize=address,undefined' -fno-omit-frame-pointer` a cada comando de compilación (las comillas evitan que PowerShell interprete la coma). Las autopruebas verifican lluvia en ambos sentidos con fixtures explícitos, independientes de la polaridad elegida para el equipo. Los escenarios FSM usan valores de lluvia/sin lluvia acordes con la configuración activa. Las expectativas temporales conservan los tiempos iniciales acordados.

Cobertura de autopruebas: extremos/rango/calibración invertida de humedad, progreso y PWM; histéresis 20/25, interrupción de estabilidad, reinicio del ciclo, confirmación y fin de lluvia, condiciones simultáneas, rebote/pulsación sostenida/liberación, cadencias, corte a 30 s, reentrada y desborde temporal. También topic/payload inválidos, buzón acotado, prioridad/caducidad/desconexión y programación de publicaciones por ciclo, reconexión y lectura válida.

El segundo ejecutable llama al **switch real de la FSM**, a `procesarEntradas()` del `.ino` y a las funciones reales de `Intercambio.cpp`, sin duplicar sus reglas. Incluye inicio/finalización idempotentes, prioridad de lluvia, máximo de humedad, silencio sin nuevas muestras, reentrada, lectura válida y nuevo ciclo. Inyecta una recepción a t=101 antes de que el consumidor que venía de t=100 adquiera el bloqueo; una muestra B con pulsación pendiente detrás de A; aceptación justo antes del corte; pulsación mantenida y nueva pulsación posterior; finalización con muestras viejas en espera y desborde de contadores. **Los dobles no prueban FreeRTOS, exclusión mutua real, periféricos ni el cliente MQTT/TCP. ASan/UBSan no demuestran ausencia de carreras lógicas. No agregar los dobles al proyecto Wokwi ni a la compilación ESP32.**

### Correcciones del review — 17/10/2026

Antes de corregir se reprodujeron los tres defectos sobre el código de `4be5a33`: pérdida de `FINALIZAR` por tiempo anterior a la recepción; reinicio por muestra B atrasada; autopruebas de control y FSM fallando al invertir la polaridad de lluvia.

1. **Reloj/buzón:** `tomarComando()` ya no acepta un tiempo obtenido por el llamador. Tanto la recepción como el consumo muestrean el reloj dentro del bloqueo de `Intercambio.cpp`; se conserva la resta modular y la caducidad real.
2. **Pulsaciones:** lectura digital, antirrebote y registro del contador se ordenan bajo el mismo bloqueo que el corte de finalización. La FSM descarta todos los eventos aceptados antes de ese corte, incluso muestras pendientes de entrega; una pulsación posterior sigue operativa. No se depende de vaciar la cola ni de acertar manualmente una ventana de 50 ms. El orden serial impide recorrer contadores hacia atrás ante muestras anteriores al corte.
3. **Lluvia:** se comparte `detectarLluvia()` entre producción y autopruebas y se prueban ambos sentidos sin cambiar la calibración del repositorio. El script compila y ejecuta las suites completas con cada valor de `LLUVIA_CON_ADC_ALTO`.

**Resultado host:** 2 suites × 2 polaridades × 2 modos (normal y ASan/UBSan), todo aprobado. **Verificación posterior en Wokwi:** compilación ESP32 y regresión parcial confirmadas por el usuario el 17/10/2026, con el alcance detallado a continuación. Los checks históricos no se trasladan al firmware corregido: los escenarios no incluidos en ese reporte no se consideran repetidos. No se declara medida la frecuencia de las carreras con el scheduler real ni se da por terminada la validación física.

### Regresión parcial posterior al review — reporte del usuario, 17/10/2026

**Entorno: ESP32 simulado en Wokwi, con el firmware posterior a las correcciones.** Esta ejecución fue realizada y confirmada por el usuario, no por el agente. Se registra separada de las pruebas host y de la matriz histórica previa a las correcciones.

| Comprobación en esta ejecución | Resultado reportado por el usuario |
| --- | --- |
| Compilación ESP32 | Correcta en Wokwi. |
| Arranque | `[TEST] Control local: OK`, `[TEST] Protocolo MQTT: OK` y estado inicial `ESPERA`. |
| Monitoreo local | Continúa funcionando. No se informó una repetición individual de todos los escenarios de sensores, umbrales y alertas. |
| Finalización y nuevo inicio local | El pulsador finaliza y una nueva pulsación inicia correctamente otro ciclo. |
| Pulsador durante tráfico MQTT | Permite finalizar; el usuario no observó comportamientos inesperados. Esto no demuestra simultaneidad exacta de dos entradas en una iteración. |
| MQTT bidireccional, QoS 0 | `INICIAR` produjo estado activo y publicación de humedad; `FINALIZAR` produjo `ESPERA`. |

**Observación global del usuario:** no detectó fallos en lo ensayado. Informó capturas del arranque, FSM y mensajes MQTT adjuntas en la conversación; no se agregan rutas, enlaces ni archivos de evidencia que no estén disponibles en el repositorio.

**Alcance del cierre documental:** se registra satisfactoriamente este reporte parcial, no una aprobación completa de la matriz funcional ni una validación con hardware real. No se infiere que se hayan repetido lluvia e histéresis en todos sus límites, cadencias/plazo sonoro, validación de órdenes, fallas/reconexiones de red, ambas polaridades en Wokwi u otros escenarios no confirmados en esta ejecución. Tampoco se demuestra ausencia de carreras para todos los intercalados posibles.

**Pendientes explícitos:** caída de WiFi durante un ciclo, simultaneidad manual exacta y validación física (montaje, calibración y seguridad del buzzer). El resto de la matriz conserva sus resultados históricos, sin convertirlos en aprobaciones posteriores a las correcciones.

## Matriz de aceptación y regresión en Wokwi

Se conserva **una sola columna** de validación. `☑ I1`: aprobado por el usuario en el incremento 1. `☑ I2`: aprobado por el usuario con el firmware previo a las correcciones (`4be5a33`), según su reporte del 16/10/2026 y la confirmación posterior de Android externo. `☐ I2`: pendiente o resultado no confirmado. **Estas marcas son históricas: no aprueban automáticamente la revisión corregida.** La regresión parcial posterior del 17/10/2026 se registra en su propia sección y no modifica estas casillas ni acredita todos los casos. Registrar condiciones, tiempos/logs y evidencia real; no marcar Wokwi por inspección ni por pruebas host.

| Caso | Acción y resultado esperado | Validación |
| --- | --- | --- |
| Encendido | Con lluvia o prendas secas, permanece en espera con LED/buzzer apagados. | ☑ I1 · ☑ I2 |
| Inicio húmedo | Con ambas prendas húmedas y lluvia ADC < 2000, pulsar: monitoreo y LED proporcional. | ☑ I1 · ☑ I2 |
| Más húmeda | Una prenda a 0 % y otra a 60 %: general 60 %, progreso 50 %, PWM 127; no ropa seca. | ☑ I1 · ☑ I2 |
| Inicio seco | Ambas ≤ 20 %, sin lluvia: tras inicio y 1 s estable, ropa seca; LED máximo. | ☑ I1 · ☑ I2 |
| Histéresis | Desde seco, 21–24 % conserva seco; 25 % durante 1 s vuelve a monitoreo. Desde húmedo, 21–24 % no confirma seco. | ☑ I1 · ☑ I2 |
| Ruido de humedad | Bajar a ≤ 20 % menos de 1 s y volver a > 20 %: no confirma seco. Un nuevo descenso requiere 1 s completo. | ☑ I1 · ☑ I2 |
| Lluvia | Desde monitoreo o seco, ADC ≥ 2000 por 1 s: lluvia. Si ambos criterios se confirman juntos, gana lluvia. | ☑ I1 · ☑ I2 |
| Ruido de lluvia | Cambiar de lado del umbral menos de 1 s no cambia la condición confirmada; se prueba aparición y finalización. | ☑ I1 · ☑ I2 |
| Fin de lluvia | ADC < 2000 por 1 s: seco si ya fue confirmado; en caso contrario monitoreo. Humedad sigue clasificándose durante lluvia. | ☑ I1 · ☑ I2 |
| Pulsador | Finalizar desde cada estado activo apaga LED y sonido sin esperar al filtrado; sostenerlo no reinicia. Liberar antes de volver a pulsar. | ☑ I1 · ☑ I2 |
| Nuevo ciclo | Después de finalizar seco, iniciar con 22 %: monitoreo, sin heredar seco. | ☑ I1 · ☑ I2 |
| PWM | H = 100/60/20/0 → progreso 0/50/100/100. En monitoreo/lluvia PWM 0/127/255/255. | ☑ I1 · ☑ I2 |
| Cadencias | Seco: alternancia cada 1,2 s; lluvia: cada 0,3 s. Misma frecuencia de tono. | ☑ I1 · ☑ I2 |
| Plazo | Mantener una alerta más de 30 s: silencio, sin cambiar estado/LED. Continuar otros 5 s: no reanuda. | ☑ I1 · ☑ I2 |
| Reentrada | Cambiar de seco a lluvia, o salir y regresar a una alerta: nuevo plazo de 30 s. | ☑ I1 · ☑ I2 |
| Reinicio | Reiniciar ESP32 durante un ciclo: espera, actuadores apagados, nuevo inicio explícito. | ☑ I1 · ☑ I2 |
| Conexión MQTT | WiFi y broker disponibles: conectar, enviar suscripción y recibir `ESPERA` desde un cliente externo. | ☑ I2 |
| Estado periódico | En espera y en cada estado activo, estado vigente cada 5 s, no retenido. | ☑ I2 |
| Inicio remoto | `INICIAR` desde espera con ropa húmeda, seca o lluvia: estado correcto tras estabilidad, lectura vigente y actuadores coherentes. | ☑ I2 |
| Finalización remota | `FINALIZAR` desde cada estado activo: espera y actuadores apagados, sin esperar a estabilidad. | ☑ I2 |
| Órdenes repetidas | Repetir `INICIAR` activo y `FINALIZAR` en espera: no alterna ni reinicia sonido, filtros o ciclo. | ☑ I2 |
| Órdenes inválidas | Topic distinto, vacío, minúsculas, espacios, salto de línea o contenido desconocido: sin cambios físicos. | ☑ I2 |
| Humedad MQTT | Máximo de ambos broches, entero 0–100; al iniciar/reconectar con lectura válida y cada 5 s activo. En espera no publica. | ☑ I2 |
| Publicación por transición | Pasar por monitoreo, seco, lluvia y espera: cliente observa los nombres MQTT correctos. | ☑ I2 |
| Heartbeat y buzzer | Estados periódicos y `INICIAR` repetido durante alerta no extienden 30 s ni reactivan sonido vencido. | ☑ I2 |
| Inicio sin WiFi/broker | Con SSID incorrecto o broker inaccesible, los 16 casos locales siguen operativos mientras reintenta. | ☑ I2 |
| Caída MQTT durante ciclo | Forzar desconexión del propio clientId: pulsador, LED, lecturas y corte sonoro siguen funcionando. | ☑ I2 |
| Reconexión MQTT | Sin reiniciar, conserva ciclo/plazo; vuelve a informar estado y humedad actuales, sin ejecutar órdenes históricas. | ☑ I2 |
| Caída WiFi durante ciclo | Interrumpir solo la red, no el ESP32: control local intacto; al restaurar conserva ciclo. Si Wokwi no permite inducirla, pendiente de entorno controlable. | ☐ I2 |
| Reinicio conectado | Reiniciar durante alerta: cliente recibe `ESPERA` al reconectar; no reanuda ni publica humedad del ciclo anterior. | ☑ I2 |
| Control local/remoto simultáneo | Finalización gana al inicio en la misma iteración de control. Cubierto por test host; prueba manual no confirmada, ver procedimiento debajo. | ☐ I2 |
| No retenidos | Con Retain desactivado, un cliente recién suscrito espera una publicación nueva; no se presentan publicaciones como historia del ciclo. Revisar retenidos ajenos antes de empezar. | ☑ I2 |
| Cliente Android externo | Envío/recepción bidireccional en teléfono confirmado por el usuario; nombre y versión de la app pendientes de registrar. | ☑ I2 |

El pulsador del esquema tiene `bounce: "0"`: la simulación del pulsador no demuestra rechazo de rebote. Las autopruebas inyectan rebote temporal sintético sin cambiar el esquema. Las marcas de I1/I2 son los resultados reportados por el usuario, no ejecuciones de Wokwi realizadas por el agente. La regresión local completa corresponde al firmware de comunicación **anterior a las correcciones**; la ejecución posterior del 17/10/2026 tiene únicamente el alcance parcial descrito arriba.

### Pendientes del incremento 2

- **Control simultáneo:** el objetivo es comprobar que una finalización no sea anulada por un inicio recibido en la misma iteración. Desde un ciclo activo, intentar pulsar CICLO para finalizar al mismo tiempo que otro operador publica `INICIAR`; si coinciden en la misma iteración, debe terminar en `ESPERA`. También, desde espera, intentar iniciar con CICLO mientras se recibe `FINALIZAR`: si coinciden, permanece en espera. Usar QoS 0, Retain desactivado y una sola pulsación/orden por intento, sin publicaciones periódicas automáticas.
- **Límite de la prueba manual:** la recepción por red, el antirrebote y el muestreo de 50 ms impiden garantizar coincidencia con la mano. Si el inicio se procesa después de una finalización ya concluida, iniciar otro ciclo es válido; no es una falla de prioridad. Comparar logs sin asumir que prueban el mismo tick. Los tests host cubren coincidencia en una iteración y los intercalados del review; no equivalen a concurrencia real del scheduler. Por decisión del usuario la prueba manual queda pendiente, no se exige acertar esa ventana para ejecutar las regresiones automatizadas.
- **Cliente Android externo:** validado y funcionando según el usuario. Falta registrar nombre y versión de la app. Esta aprobación corresponde al firmware previo a las correcciones, no sustituye la futura app propia ni la conectividad del circuito físico, y requiere regresión con el firmware actualizado.
- **Caída WiFi durante ciclo:** diferida por falta de un mecanismo controlable en el entorno de simulación utilizado. No se marca aprobada por haber probado caída MQTT o arranque sin red. Para completarla en hardware: iniciar ciclo en una red de prueba controlada, interrumpir su punto de acceso sin apagar el ESP32, verificar pulsador/sensores/LED y corte del buzzer, restaurar la red y comprobar estado actual sin reinicio ni rearmado de la alerta. Preparar antes credenciales locales, calibración y seguridad del circuito. No interrumpir una red compartida sin autorización.

## Registro de verificación de este incremento

Entorno comprobado el 15/10/2026: Windows, Ubuntu/WSL, `g++ (Ubuntu 15.2.0-16ubuntu1) 15.2.0`. Ejecutable generado fuera del repositorio.

| Verificación | Resultado |
| --- | --- |
| Compilación de autopruebas C++11 en Ubuntu/WSL (`g++`, advertencias como errores) | Ejecutada, sin errores |
| Ejecución de autopruebas en Ubuntu/WSL | Ejecutada: `Control local: OK` |
| Autopruebas con AddressSanitizer y UndefinedBehaviorSanitizer | Ejecutadas: `Control local: OK`, sin diagnósticos |
| FSM real con dobles de GPIO/red/RTOS, C++11 y sanitizadores | Ejecutada: `FSM real con dobles de GPIO/red/RTOS: OK`, sin diagnósticos |
| Autopruebas del protocolo y programación MQTT, C++11 y sanitizadores | Ejecutadas: `Protocolo MQTT: OK`, sin diagnósticos |
| Correcciones del review, 17/10/2026: control/protocolo y FSM/intercambio real con dobles | Ejecutadas con lluvia ADC alto y bajo, normales y ASan/UBSan: 8/8 combinaciones aprobadas |
| Compilación ESP32 y arranque después de las correcciones, 17/10/2026 | Correctos según el usuario en Wokwi; ambas autopruebas OK y estado inicial `ESPERA` |
| Regresión parcial Wokwi/MQTT después de las correcciones, 17/10/2026 | Usuario confirmó monitoreo local, finalización/reinicio por pulsador, finalización con tráfico MQTT e intercambio `INICIAR`/humedad/`FINALIZAR`/`ESPERA`, QoS 0; sin fallos observados en lo ensayado |
| Matriz completa después de las correcciones | **No aprobada en su totalidad**: esta ejecución fue parcial; no se trasladan las marcas históricas ni se infieren casos no reportados |
| Compilación del firmware en Wokwi, incremento 1 | Confirmada por el usuario, junto con autopruebas OK en terminal |
| Matriz local en Wokwi, incremento 1 | 16/16 casos aprobados por el usuario; ver columna de validación |
| Compilación ESP32 y autopruebas en Wokwi, incremento 2 previo a las correcciones | Confirmadas por el usuario, con logs de arranque/conexión |
| Regresión local en Wokwi, incremento 2 previo a las correcciones | 16/16 casos aprobados por el usuario el 16/10/2026 |
| MQTT / WiFi, incremento 2 previo a las correcciones | Intercambio bidireccional, cliente Android externo, validación, publicaciones, caída/reconexión MQTT y arranque sin red aprobados por el usuario; caída WiFi durante ciclo pendiente |
| Matriz histórica, incremento 2 previo a las correcciones | 31/33 aprobados por el usuario; control simultáneo manual y caída WiFi diferidos. Nombre/versión del cliente Android sin informar |
| Caída WiFi durante ciclo y simultaneidad manual exacta | **Pendientes**; finalizar mientras hay tráfico MQTT no acredita estas dos condiciones |
| Pruebas físicas | **Pendientes**, no inferidas de simulación ni autopruebas |

### Diferencias respecto de la referencia manual

Transporte coherente por valor y contador de pulsaciones en lugar de datos globales sin sincronizar; última muestra en vez de acumulación de eventos ambientales; clasificación y filtrado propiedad de la FSM; histéresis y estabilidad; corte sonoro de 30 s sin rearmado por repetición; verificación de creación de tareas y autopruebas. Se mantiene el patrón de estados/eventos y switch anidados. Se agrega una tercera tarea que aísla las esperas WiFi/MQTT, topics y clientId propios, validación estricta y buzón de órdenes acotado, sin acceso de red a actuadores.

## Antes del hardware

Confirmar placa, alimentación, conexiones y medir la calibración real de ambos broches y lluvia. El porcentaje es un índice estimado de conductividad, no humedad certificada. Un broche desconectado puede parecer ropa seca: mantener ambos colocados y finalizar el ciclo al retirar las prendas.

El buzzer Wokwi es pasivo; su uso con `tone()` **no valida el módulo físico**. Confirmar activo/pasivo, polaridad, tensión/corriente y necesidad de etapa de manejo antes de conectarlo. El rótulo “Buzzer (5V)” del dibujo no autoriza conectar una carga de 5 V directamente al GPIO. No aplicar más de la tensión admisible a las entradas del ESP32.
