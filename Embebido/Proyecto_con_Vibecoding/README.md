# Firmware IA — incremento 2: control local + WiFi/MQTT

Implementación nueva para Arduino-ESP32 y el circuito de [`../diagram.json`](../diagram.json), sin modificar la variante manual ni el esquema. El usuario confirmó compilación, autopruebas, conexión WiFi/MQTT e intercambio bidireccional del incremento 2, además de la regresión de los 16 casos locales. **Matriz I2: 30/33 casos aprobados; pendientes caída WiFi durante ciclo, validación manual del control simultáneo y cliente Android externo. No incluye app Android propia ni completa V2.**

## Archivos y dependencias

- `Proyecto_con_Vibecoding.ino`: inicialización, tareas de sensores/FSM, cola y actuadores.
- `Comunicacion.cpp/.h`: tercera tarea FreeRTOS, WiFi/MQTT e intercambio sincronizado con la FSM.
- `ProtocoloMqtt.h`: estados/eventos, validación, buzón de comandos y programación de publicaciones, sin dependencia de Arduino.
- `Configuracion.h`: GPIO, calibración provisional, umbrales, tiempos y broker/topics.
- `ConfiguracionWifi.ejemplo.h`: red pública de simulación, sin secretos. El archivo local opcional `ConfiguracionWifi.h` está ignorado por Git.
- `ControlLocal.h`: cálculos, estabilidad, histéresis, antirrebote y plazo sonoro.
- `PruebasControl.h` / `PruebasMqtt.h`: autopruebas sin GPIO ni esperas, antes de crear tareas. Una falla detiene el control con actuadores apagados e indica la línea.
- `pruebas/control_local.cpp`: ejecuta esas autopruebas en una computadora.
- `pruebas/fsm.cpp` y `pruebas/dobles/`: ejecutan la FSM real con entradas inyectadas, reloj y GPIO sustituidos, sin scheduler ni red.

Se requiere el core **Arduino-ESP32**, sus componentes incluidos Arduino/FreeRTOS/WiFi, y **PubSubClient 2.8**, fijada en `libraries.txt`. `analogWrite()`, `tone()` y `noTone()` son las APIs de actuadores. La preconexión TCP con timeout explícito usa la firma disponible en core 2.0.17 y 3.3.0 (comprobada en sus headers publicados); **eso no sustituye una compilación ESP32**. No se fija una versión del core no probada. Registrar la que utilice Wokwi al compilar.

## Compilar y probar en Wokwi web

No hay integración automática con Wokwi en este entorno. Procedimiento para ejecución asistida:

1. Abrir un proyecto Arduino ESP32 en Wokwi, con ESP32 DevKit v1.
2. Pegar el contenido de `Proyecto_con_Vibecoding.ino` en `sketch.ino`.
3. Agregar los `.h` versionados y `Comunicacion.cpp` de este directorio, conservando los nombres. Incluir `ConfiguracionWifi.ejemplo.h`; no subir credenciales reales ni el directorio `pruebas/`. Agregar también `libraries.txt`, con `PubSubClient@2.8`.
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
- `tareaSensores()` adquiere entradas cada 50 ms y aplica antirrebote de 40 ms. Un flanco estable de pulsación incrementa un contador; mantener presionado no repite acciones. La liberación también debe estabilizarse antes de otra pulsación.
- Envía por valor la muestra completa y el contador acumulativo a una cola de longitud 1 mediante `xQueueOverwrite()`. No comparte una estructura mutable de lecturas ni se bloquea esperando espacio.
- `tareaFSM()` recibe la última muestra, convierte cada pulsación pendiente en `Evento::BOTON_CICLO` y luego procesa las condiciones actuales. Así no se pierde una pulsación aunque su muestra original haya sido sobrescrita, ni se forma una cola de decisiones ambientales antiguas.
- A diferencia de la referencia, la clasificación temporal de seco/lluvia y la generación de sus eventos se realizan **en la tarea FSM**, para que el inicio pueda reiniciar la clasificación sin acceso concurrente. Los eventos siguen siendo explícitos (`enum class Evento`) y `ejecutarFSM()` conserva el **switch de estado con switch de evento dentro de cada estado**. No se usa otra arquitectura de FSM.
- La FSM es la única dueña del estado, filtros del ciclo y actuadores después de iniciar las tareas. `cambiarEstado()` centraliza transiciones, efectos inmediatos y registros.
- Solo la tarea de comunicación utiliza WiFi, el socket y `PubSubClient`. La FSM intercambia instantáneas y comandos bajo una sección crítica breve: no se hacen llamadas de red, registros ni esperas de FreeRTOS dentro de ella. La tercera tarea aísla las esperas de red; por sí sola no constituye una garantía absoluta contra deadlocks.
- El buzón remoto tiene capacidad para una orden pendiente. Repeticiones no se acumulan ni rejuvenecen el plazo; `FINALIZAR` reemplaza un `INICIAR` pendiente, nunca al revés. Se descartan órdenes al detectar desconexión y al cumplir 1000 ms sin consumo. No hay persistencia ni reproducción tras reconexión.
- `INICIAR`/`FINALIZAR` son eventos explícitos en los switch existentes. Iniciar un ciclo activo y finalizar en espera no cambian estado, filtros ni plazo sonoro. En una misma iteración, `FINALIZAR` remoto prevalece y consume las pulsaciones coincidentes para evitar un reinicio inmediato; una finalización local también gana a `INICIAR` remoto.
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

Desde este directorio, con `g++` disponible, colocar el ejecutable fuera del repositorio. En el entorno Windows actual se encontró `g++` en Ubuntu/WSL:

```powershell
wsl.exe -d Ubuntu --exec g++ -std=c++11 -Wall -Wextra -Werror -pedantic pruebas/control_local.cpp -o /mnt/c/Users/Mateo/AppData/Local/Temp/opencode/control_local_tests
wsl.exe -d Ubuntu --exec /mnt/c/Users/Mateo/AppData/Local/Temp/opencode/control_local_tests
wsl.exe -d Ubuntu --exec g++ -std=c++11 -Wall -Wextra -Werror -pedantic -I pruebas/dobles pruebas/fsm.cpp -o /mnt/c/Users/Mateo/AppData/Local/Temp/opencode/fsm_mqtt_tests
wsl.exe -d Ubuntu --exec /mnt/c/Users/Mateo/AppData/Local/Temp/opencode/fsm_mqtt_tests
```

La ruta de salida es específica de esta computadora; adaptarla en otro entorno. Resultados esperados: `Control local: OK`, `Protocolo MQTT: OK` y `FSM real con dobles de GPIO/red/RTOS: OK`, salida 0. Para ejecutar también con sanitizadores, agregar `'-fsanitize=address,undefined' -fno-omit-frame-pointer` a cada comando de compilación (las comillas evitan que PowerShell interprete la coma). Las pruebas usan los umbrales, sentidos de lluvia y tiempos iniciales acordados; si se modifica esa configuración, revisar también sus expectativas.

Cobertura de autopruebas: extremos/rango/calibración invertida de humedad, progreso y PWM; histéresis 20/25, interrupción de estabilidad, reinicio del ciclo, confirmación y fin de lluvia, condiciones simultáneas, rebote/pulsación sostenida/liberación, cadencias, corte a 30 s, reentrada y desborde temporal. También topic/payload inválidos, buzón acotado, prioridad/caducidad/desconexión y programación de publicaciones por ciclo, reconexión y lectura válida.

El segundo ejecutable llama al **switch real de la FSM** y a `procesarEntradas()` del `.ino`, sin duplicar sus reglas. Verifica inicio/finalización idempotentes desde todos los estados, prioridad de lluvia, máximo de humedad, silencio sin nuevas muestras, reentrada, finalizaciones simultáneas, invalidez de lecturas y reinicio de clasificación. **Los dobles no prueban FreeRTOS, exclusión mutua real, periféricos ni el cliente MQTT/TCP. No agregarlos al proyecto Wokwi ni a la compilación ESP32.**

## Matriz de aceptación y regresión en Wokwi

Se conserva **una sola columna** de validación. `☑ I1`: probado y aprobado por el usuario en el incremento 1. `☑ I2`: aprobado por el usuario al repetir/probar con el firmware de comunicación, según su reporte del 16/10/2026. `☐ I2`: pendiente o resultado no confirmado. Las marcas de I1 son históricas, **no aprueban automáticamente I2**. Registrar condiciones, tiempos/logs y evidencia real; no marcar Wokwi por inspección del código ni por autopruebas de computadora.

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
| Cliente Android externo | Repetir envío/recepción bidireccional con app de Play Store en teléfono y registrar herramienta/versión. | ☐ I2 |

El pulsador del esquema tiene `bounce: "0"`: la simulación del pulsador no demuestra rechazo de rebote. Las autopruebas inyectan rebote temporal sintético sin cambiar el esquema. Los resultados de ambas columnas son los reportados por el usuario, no ejecuciones de Wokwi realizadas por el agente. Se confirmó la regresión local completa con el firmware de comunicación.

### Pendientes del incremento 2

- **Control simultáneo:** el objetivo es comprobar que una finalización no sea anulada por un inicio recibido en la misma iteración. Desde un ciclo activo, intentar pulsar CICLO para finalizar al mismo tiempo que otro operador publica `INICIAR`; si coinciden en la misma iteración, debe terminar en `ESPERA`. También, desde espera, intentar iniciar con CICLO mientras se recibe `FINALIZAR`: si coinciden, permanece en espera. Usar QoS 0, Retain desactivado y una sola pulsación/orden por intento, sin publicaciones periódicas automáticas.
- **Límite de la prueba manual:** la recepción por red, el antirrebote y el muestreo de 50 ms impiden garantizar coincidencia con la mano. Si el inicio se procesa después de una finalización ya concluida, iniciar otro ciclo es válido; no es una falla de prioridad. Comparar logs de recepción y transiciones, sin asumir que prueban el mismo tick. `pruebas/fsm.cpp` inyecta ambas entradas en una misma llamada a `procesarEntradas()` y comprueba la prioridad de forma determinista; no equivale a una prueba de concurrencia real del scheduler. La casilla Wokwi queda pendiente hasta aclarar el resultado, no se marcó el “Correcto?” del reporte.
- **Cliente Android externo:** instalar una app cliente MQTT desde Play Store (por ejemplo, IoT MQTT Panel), con broker `broker.emqx.io:1883`, TCP sin TLS/autenticación, sesión limpia y clientId propio, distinto del ESP32 y del cliente de computadora. Suscribirse a estado/sensor y publicar `INICIAR`/`FINALIZAR` en comando, QoS 0 y Retain desactivado. Confirmar recepción de estado y humedad, y finalización real en Wokwi. Registrar nombre y versión de la app; esta prueba no sustituye la futura app propia ni la conectividad del circuito físico.
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
| Compilación del firmware en Wokwi, incremento 1 | Confirmada por el usuario, junto con autopruebas OK en terminal |
| Matriz local en Wokwi, incremento 1 | 16/16 casos aprobados por el usuario; ver columna de validación |
| Compilación ESP32 y autopruebas en Wokwi, incremento 2 | Confirmadas por el usuario, con logs de arranque/conexión |
| Regresión local en Wokwi, incremento 2 | 16/16 casos aprobados por el usuario el 16/10/2026 |
| MQTT / WiFi, incremento 2 | Intercambio bidireccional, validación, publicaciones, caída/reconexión MQTT y arranque sin red aprobados por el usuario; caída WiFi durante ciclo y cliente Android externo pendientes |
| Matriz completa, incremento 2 | 30/33 aprobados por el usuario; control simultáneo manual no confirmado, caída WiFi diferida por entorno y cliente Android pendiente |
| Pruebas físicas | **Pendientes**, no inferidas de simulación ni autopruebas |

### Diferencias respecto de la referencia manual

Transporte coherente por valor y contador de pulsaciones en lugar de datos globales sin sincronizar; última muestra en vez de acumulación de eventos ambientales; clasificación y filtrado propiedad de la FSM; histéresis y estabilidad; corte sonoro de 30 s sin rearmado por repetición; verificación de creación de tareas y autopruebas. Se mantiene el patrón de estados/eventos y switch anidados. Se agrega una tercera tarea que aísla las esperas WiFi/MQTT, topics y clientId propios, validación estricta y buzón de órdenes acotado, sin acceso de red a actuadores.

## Antes del hardware

Confirmar placa, alimentación, conexiones y medir la calibración real de ambos broches y lluvia. El porcentaje es un índice estimado de conductividad, no humedad certificada. Un broche desconectado puede parecer ropa seca: mantener ambos colocados y finalizar el ciclo al retirar las prendas.

El buzzer Wokwi es pasivo; su uso con `tone()` **no valida el módulo físico**. Confirmar activo/pasivo, polaridad, tensión/corriente y necesidad de etapa de manejo antes de conectarlo. El rótulo “Buzzer (5V)” del dibujo no autoriza conectar una carga de 5 V directamente al GPIO. No aplicar más de la tensión admisible a las entradas del ESP32.
