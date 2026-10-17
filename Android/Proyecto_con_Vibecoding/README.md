# Android IA — incrementos 1 y 2

Aplicación implementada sobre el esqueleto del usuario en `SistemaDeteccionRopaSecaIA/`, con Java, layouts XML y cuatro Activities reales. El incremento 2 agrega MQTT y control remoto. No se modificaron firmware, esquema, variante manual, versiones de herramientas ni applicationId (`com.unlam.soa.grupo3.ropaseca.ia`). Se agregó únicamente la dependencia de transporte Paho Java 1.2.5, sin el servicio Android de Paho.

**Incremento 1 cerrado por aceptación del usuario.** Tras la corrección mínima de recorte, el usuario confirmó que el defecto observado quedó resuelto sin regresiones en las pruebas realizadas. **Incremento 2 no aceptado por completo:** los dos defectos de ordenamiento local se corrigieron con regresiones locales y el reviewer comprobó posteriormente integración app ↔ firmware IA en Wokwi, sin nuevos defectos de Android en los escenarios ensayados. Restan casos de segundo plano con comando pendiente, transiciones de alerta/deduplicación prolongada y las comprobaciones complementarias indicadas al final. La integración con simulación no valida el circuito físico.

## Uso y decisiones

- **Inicio (`MainActivity`):** nombre «Ropa seca IA», estado de comunicación y condición de la estación, resultado del último comando y accesos a las otras tres pantallas.
- **Monitoreo:** estado desconocido hasta recibir información válida. Inicio en espera; finalización en cualquier estado activo. El botón solo se habilita con broker y estado vigentes y sin otro comando pendiente. Humedad y progreso reales, con antigüedad independiente del estado. Al quedar desactualizada una lectura se indica expresamente y se oculta la barra; en espera se borran los datos del ciclo anterior. El ESP32 sigue siendo la autoridad: pulsar no cambia la pantalla a un estado supuesto.
- **Condiciones (`CondicionesActivity`):** lectura real de `Sensor.TYPE_LIGHT`, sin permisos especiales ni MQTT. Acercar el teléfono al lugar de la ropa sin cubrir el sensor. Registra el listener en `onResume`, lo libera en `onPause` y borra la lectura al reanudar para esperar una nueva. Explica ausencia de hardware, fallo de registro y lecturas inválidas, sin bloquear las demás pantallas. No estima duración del secado ni modifica el ESP32.
- **Conexión:** separa broker conectado con suscripciones confirmadas de comunicación reciente con la estación. Muestra antigüedad del último estado y broker/topics tomados de las constantes ejecutables, sin duplicar esa configuración en strings XML.
- Navegación: `Inicio → Monitoreo / Condiciones / Conexión → Inicio`. «Volver a Inicio» finaliza la Activity secundaria; Atrás usa la pila normal de Android. No se crean copias de Inicio para volver.
- `Pantalla.java` comparte únicamente el ajuste de barras del sistema y recortes de pantalla. Cada layout tiene desplazamiento vertical, tamaños de contenido flexibles y estilos comunes compatibles con el tema claro/oscuro del esqueleto. Los cuatro ScrollView conservan `clipToPadding="true"` para que el contenido desplazado no invada los insets. La revisión externa comprobó acceso al botón con fuente al 200 % en Condiciones horizontal; el usuario confirmó posteriormente la resolución del defecto de recorte sin regresiones en las pruebas realizadas.
- `Iluminacion.java` contiene los umbrales ajustables y clasificación, independiente de Android para probarla con JUnit. Su manejo del sensor no depende de MQTT.
- Las cuatro Activities muestran la condición actual y el resultado del comando compartidos. Un cambio observado a lluvia/ropa seca genera además un Snackbar, sin sonido ni vibración. Un heartbeat, navegar o reabrir no repite avisos. El primer estado después de conectar se presenta como condición actual, sin reproducir un evento histórico.

### Comunicación y estado compartido

- `ConfiguracionMqtt.java`: única configuración ejecutable de broker, puerto, topics y plazos. Cambiar y recompilar, sin pantalla de configuración. Mantenerla coordinada con `Configuracion.h` del firmware.
- `ModeloEstacion.java`: validación y reglas de vigencia, progreso, comandos y cambios de alerta, sin dependencias Android. Solo se modifica desde el hilo principal; recibe tiempos monotónicos (`SystemClock.elapsedRealtime` en la app).
- `MqttManager.java`: una instancia por proceso, propiedad de `RopaSecaApp`. Un worker serial posee el cliente desde su creación, incluso mientras espera CONNACK/SUBACK, y realiza conexión, suscripción, publicación y cierre. Antes de otro intento cierra el anterior; si se cancela mientras bloquea, espera su salida/timeout en ese mismo worker, sin abrir otro socket en paralelo. Los callbacks actualizan modelo/UI en main. Un identificador de sesión descarta callbacks/envíos de conexiones anteriores. Las Activities solo agregan/quitan su listener mediante `EstacionActivity`, sin crear clientes propios.
- `RopaSecaApp.java`: observa las Activities iniciadas. Al ocultar todas espera 500 ms para tolerar navegación/recreación y cierra el cliente; no envía `FINALIZAR`. No hay servicio, wakelock ni garantía de alertas fuera de primer plano. Al volver después del cierre se espera información nueva. La tolerancia breve no garantiza conservar la conexión en recreaciones prolongadas; reconectar es seguro.
- Reintento tras fallo: 5 s. Keepalive: 5 s. Conexión: timeout de socket de 5 s y espera Paho de 6 s; operaciones de suscripción/publicación: 3 s. DNS, scheduler y cierre pueden añadir tiempo; no son un límite absoluto de toda operación. No bloquean la UI ni el firmware.
- Estado y humedad vigentes durante menos de 15 s desde cada recepción válida; al alcanzar 15 s dejan de ser actuales. Un reloj compartido revisa plazos aproximadamente cada segundo mientras está activa la comunicación. Un estado no rejuvenece la humedad y una humedad no prueba presencia de la estación.
- Comando en dos fases: primero «Preparando la publicación», con máximo de 10 s en cola (`MAXIMO_COLA_MS`), sujeto también a vigencia/sesión. Solo después de que `publish()` retorna exitosamente comienza la confirmación de 10 s (`CONFIRMACION_MS`), sin reiniciarla por heartbeat ni navegación. Un bloqueo prolongado no deja la UI pendiente indefinidamente y un marcador tardío no revive una orden vencida.
- Solo confirma un callback capturado después de la publicación exitosa: el worker primero encola su marcador en main y luego expone una etiqueta `volatile`, que el callback captura como primera operación, antes de copiar datos o encolarse. Así, todo callback con etiqueta nueva se procesa después del marcador, y uno capturado antes sigue identificado aunque se procese después o comparta el mismo milisegundo. No hay bloqueo alrededor de I/O ni un ID enviado al firmware. Un estado compatible anterior al envío actualiza la condición observada, pero no confirma ni cancela una orden aún en cola. Una respuesta capturada durante `publish()` se descarta para confirmación y se espera otro estado periódico válido.
- Inicio se confirma con cualquier estado activo posterior, incluso lluvia o ropa seca; finalización, con `ESPERA`. Si vence el plazo o se pierde conexión se informa «no confirmado», nunca «no se ejecutó»; para finalizar se ofrece la alternativa del pulsador físico. Que `publish()` retorne bien tampoco garantiza ejecución: sigue siendo necesaria la observación del estado.
- Sin almacenamiento offline ni persistencia de comandos/lecturas. Se publica QoS 0 sin retain, con sesión limpia y sin reconexión automática del objeto usado para un envío. Se comprueba sesión y caducidad también antes de publicar en el worker; una orden en tránsito al perder red no puede revocarse y no se garantiza su entrega.
- Progreso: `min(100, (100 - H) * 100 / 80)`, con humedad validada de 0 a 100 y división entera truncada como en el firmware. H=100/60/20/0 produce 0/50/100/100 %. No estima tiempo restante; por histéresis, ropa seca y progreso instantáneo no son equivalentes.

| Lux válidos | Clasificación |
| --- | --- |
| 0 ≤ lux < 100 | Muy poca iluminación |
| 100 ≤ lux < 500 | Poca iluminación |
| 500 ≤ lux < 10.000 | Buena iluminación |
| lux ≥ 10.000 | Excelente iluminación |

Negativos, NaN e infinitos se rechazan sin clasificación. El valor se muestra sin redondearlo a otro intervalo. La clasificación es orientativa, no una medición del secado.

### Contrato MQTT implementado

Según `Embebido/Proyecto_con_Vibecoding/Configuracion.h` y `ProtocoloMqtt.h`:

- `broker.emqx.io:1883`, MQTT/TCP sin autenticación ni cifrado. Broker público académico; no enviar datos sensibles.
- Prefijo `unlam/soa/grupo3/lavadero-ia/`.
- `comando`: `INICIAR` / `FINALIZAR`.
- `estado`: `ESPERA`, `MONITOREANDO_SECADO`, `ROPA_SECA`, `LLUVIA`.
- `sensor`: humedad general entera de 0 a 100, sin `%`.
- QoS 0, mensajes no retenidos; nunca retener ni reenviar comandos offline.

La app agrega permiso `INTERNET`, sin permisos de notificaciones ni servicio en segundo plano. Topics y estados deben coincidir exactamente: sin trim, cambios de mayúsculas ni sufijos. Humedad: 1–3 dígitos ASCII en rango; se rechazan signos, decimales, espacios, `%`, nulos, tamaños excesivos y mensajes desconocidos. Solo un estado válido no retenido habilita presencia; la humedad solo se acepta con estado activo vigente. Los mensajes retenidos se descartan para no presentar datos viejos como actuales. No se publica nada automáticamente al abrir/cerrar la app.

**Limitaciones del contrato:** no tiene identificador de comando, ciclo ni timestamp de adquisición. Un estado observado confirma la condición buscada, pero no atribuye el cambio a un cliente concreto. Se borra la humedad al observar espera, comenzar un ciclo observado, reconectar o recuperar estado tras caducidad; no se puede detectar con certeza un ciclo completo cuya espera nunca se recibió. La antigüedad mide recepción, no edad física de la muestra. El broker público no autentica a la estación: terceros podrían inyectar mensajes válidos. TLS/autenticación requerirían una adaptación coordinada, no están implementados.

## Compilar y probar

Abrir `SistemaDeteccionRopaSecaIA/` en Android Studio. Se preservan Gradle 9.6.0, AGP 9.4.1, JVM del daemon 25, código Java 11, minSdk 28 y compileSdk/targetSdk 37. Configurar el SDK local mediante Android Studio, `local.properties` o entorno; no versionar rutas locales.

Desde `Android/Proyecto_con_Vibecoding/SistemaDeteccionRopaSecaIA/`, PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:testDebugUnitTest
```

Si la consola no encuentra Java, establecer `JAVA_HOME` solo para esa sesión. En esta computadora se utilizó el JDK existente de Android Studio:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
```

Adaptar esa ruta a la instalación propia. No es necesario cambiar las versiones del proyecto.

Para ejecutar solo las pruebas funcionales nuevas:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.unlam.soa.grupo3.ropaseca.ia.IluminacionTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.unlam.soa.grupo3.ropaseca.ia.ModeloEstacionTest"
```

Con un teléfono/emulador conectado y autorizado por ADB:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

`MqttManagerTest` usa Paho real contra `BrokerLocal`, un doble TCP/MQTT mínimo en loopback del dispositivo: comprueba sesión limpia, topics/QoS de suscripción, comando sin retain, doble pulsación, confirmación, corte/reconexión sin replay y timeout sin Activities. **No contacta al broker público ni acciona el ESP32.** El constructor de manager que permite indicar URI se utiliza solo para esa prueba; la app usa siempre `ConfiguracionMqtt.URI`. `PantallasTest` recorre las cuatro Activities, botón/Atrás y recreación; otro caso usa datos explícitos de fixture para verificar representación de humedad/progreso, caducidad y control. No presentar ese fixture como lecturas de firmware real. La navegación puede intentar conectar al broker configurado, pero no publica comandos.

Para repetir únicamente las pruebas de transporte y regresiones sin contactar al broker público, ejecutar **cada clase por separado** y comprobar el número de tests en el informe de cada ejecución:

```powershell
.\gradlew.bat --no-configuration-cache :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.unlam.soa.grupo3.ropaseca.ia.MqttRegresionTest"
.\gradlew.bat --no-configuration-cache :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.unlam.soa.grupo3.ropaseca.ia.MqttManagerTest"
```

La primera clase tiene 2 tests y la segunda 1. Los informes de `connectedDebugAndroidTest` se reemplazan en cada ejecución. El constructor de manager con worker inyectable se usa solo para retener de forma determinista el executor serial en las pruebas; la app sigue creando un único worker propio.

Informes locales: `app/build/reports/tests/testDebugUnitTest/index.html` y `app/build/reports/lint-results-debug.html`. APK local: `app/build/outputs/apk/debug/app-debug.apk`; instalar desde Android Studio en un dispositivo autorizado o emulador. No versionar ninguno de esos artefactos, `local.properties` ni secretos. El wrapper y su JAR se conservan.

## Integración posterior app ↔ firmware IA en Wokwi — reporte del reviewer

Evidencia comunicada por el usuario, obtenida por el reviewer mediante **ADB y MQTT**, no repetida por el implementador en esta actualización documental. El reviewer confirmó que el APK instalado coincidía por SHA-256 con el generado localmente; no se aportó el valor del hash y no se inventa uno. No reportó nuevos defectos de Android en los escenarios ensayados ni modificó proyecto, firmware, documentación o Git durante la revisión.

| Comprobación integrada | Resultado reportado |
| --- | --- |
| Inicio desde la app | Confirmado por estado del firmware, no solo por publicación |
| Finalización desde monitoreo, lluvia y ropa seca | Se observó `FINALIZAR → ESPERA` y confirmación en Android |
| Humedad general máxima, no promedio | Un broche al 60 % y otro seco: H=60 %, progreso=50 % |
| Otros valores de humedad/progreso | H=100 → 0 %; H=59 → 51 %; H=0 → 100 % |
| Pérdida de WiFi del teléfono | Broker desconectado, datos desactualizados, barra oculta y control bloqueado |
| Recuperación de WiFi del teléfono | Recuperó el mismo ciclo y lecturas nuevas, sin reproducir `INICIAR` |
| Finalización del ciclo | Borró humedad, progreso y antigüedad anteriores |
| Navegación y cierre del proceso | No enviaron finalización automática; al reabrir esperó datos actuales |
| Comando pendiente y rotación | El pendiente sobrevivió a la recreación y se resolvió, sin bloqueo perpetuo |
| Condiciones de alerta | Lluvia y ropa seca visibles en las cuatro Activities; Snackbar de ropa seca observado en Inicio |
| Sensor de luz del teléfono | Lecturas reales y clasificación; liberación del listener al salir |
| Regresión visual | Monitoreo horizontal con fuente al 200 %: regreso alcanzable y sin invasión de barras en lo observado |
| Comandos MQTT observados | Exactos, QoS 0 y no retenidos |

**Riesgo operativo observado:** Wokwi presentó desconexiones intermitentes. Una finalización no pudo confirmarse; Android informó la incertidumbre y ofreció el pulsador físico. Una prueba posterior sí terminó en `ESPERA`. El reporte no atribuye esa pérdida a un defecto de Android ni justifica cambiar el firmware. No se transforma el intento no confirmado en un éxito ni en prueba de que la orden no se ejecutó.

**Estado al terminar:** estación confirmada en `ESPERA` y ajustes temporales del teléfono restaurados, según el reviewer.

Capturas locales: `C:\Users\Mateo\AppData\Local\Temp\opencode\ropa-i2-*.png`. Se verificó la existencia de archivos con ese patrón al actualizar este README; no se realizó una nueva evaluación independiente de su contenido ni se copiaron al repositorio. Ejemplos: `ropa-i2-finalizar-rotacion.png`, `ropa-i2-reanudacion-wifi-desconectado.png`, `ropa-i2-reanudacion-wifi-recuperado.png`, `ropa-i2-monitoreo-horizontal-200-abajo.png` y capturas de lluvia/ropa seca en las cuatro pantallas. La ruta es evidencia temporal local, no un enlace publicado ni un archivo versionado; conservar las capturas fuera de la carpeta temporal si se necesitan para la entrega.

**Límite de esta aprobación parcial:** acredita integración con ESP32 simulado en los casos anteriores, no toda la matriz ni validación física. Mostrar la condición en cada Activity no demuestra todas las transiciones con cada una en primer plano ni una observación prolongada de deduplicación por heartbeat. Rotar con una orden pendiente no acredita pasar a segundo plano durante esa espera. Cortar WiFi del teléfono tampoco acredita cortar WiFi del ESP32, perder solo presencia de estación manteniendo el broker conectado o reiniciar el firmware. Los pendientes vigentes se enumeran al final.

## Correcciones del review del incremento 2 — 17/10/2026

Se reprodujeron los dos defectos **antes de corregir la lógica**, con Paho y el manager reales en el Moto G8 Play/API 29, usando exclusivamente loopback:

| Regresión nueva | Antes | Después |
| --- | --- | --- |
| Worker retenido, solicitar inicio y recibir lluvia antes de publicar | `CONFIRMADO` cuando el test esperaba `PENDIENTE` | Permanece pendiente y no hay publicación mientras está retenido; al liberar se publica una vez y solo un estado posterior confirma. Se repite también para `FINALIZAR` con `ESPERA` |
| CONNACK inicial demorado, detener/iniciar antes de liberarlo | Máximo observado: 2 sockets simultáneos con el mismo clientId | Máximo observado: 1; cierra el candidato cancelado antes de conectar y suscribir el nuevo intento |

Cambios acotados: `ModeloEstacion` agrega fase de publicación y exige la etiqueta de recepción posterior; `MqttManager` ordena ese marcador con callbacks y centraliza en el worker la propiedad/cierre del candidato. La UI diferencia en cola de espera de confirmación. No se modificaron firmware, contrato, esquema, versiones, dependencias ni funcionalidades excluidas.

Verificación posterior:

- `:app:assembleDebug`, `:app:lintDebug` y `:app:testDebugUnitTest`: correctos; **18 tests funcionales** (15 modelo + 3 iluminación), más la plantilla aritmética. Las pruebas agregadas incluyen ambos comandos con timestamps iguales y callbacks anteriores procesados después del marcador, plazo desde publicación, marcador duplicado y cola vencida sin reactivación.
- `MqttRegresionTest`: **2/2** instrumentados aprobados; `MqttManagerTest`, ejecutado aparte: **1/1** aprobado. Solo transporte local, sin broker público ni ESP32. El broker de prueba ahora atiende sockets concurrentemente y permite retener el primer CONNACK; el worker de prueba permite detener una publicación antes de su ejecución.
- Lint: 0 errores, las mismas 8 advertencias del esqueleto. `git diff --check`: sin errores de whitespace (avisos LF/CRLF).
- Se intentó seleccionar ambas clases en una sola invocación, pero el informe solo acreditó los 2 tests de regresión; por eso se ejecutó y verificó aparte el test de transporte. No se considera una ejecución aprobada de tests que no figuren en su informe.

**Alcance de esa ejecución local, anterior al reporte de integración:** no se repitió `PantallasTest` ni una revisión visual/sensor físico en esa sesión. Su evidencia previa recreaba Inicio sin comando pendiente y usaba fixtures para datos; por sí sola no acreditaba navegación/recreación con una confirmación real, regreso desde segundo plano ni avisos visibles integrados en las cuatro Activities. La prueba local detener/iniciar comprueba ordenamiento del manager, no sustituye esas pruebas de lifecycle de la app. Tampoco se contactó entonces al broker público ni se probó la app propia contra el firmware IA. El reporte posterior registrado arriba aporta nueva evidencia integrada, con alcance parcial; no cierra la aceptación del incremento 2.

## Evidencia inicial del incremento 2, anterior al review — 17/10/2026

Entorno: Windows 11, JDK JetBrains 25.0.3/Gradle 9.6.0, **Moto G8 Play con Android 10/API 29 autorizado por ADB**. Se instaló y ejecutó el APK debug. Sin cambios de rama, commits ni push.

| Comprobación | Resultado |
| --- | --- |
| `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest`, juntos y con JAVA_HOME temporal | Ejecución inicial anterior al review: **BUILD SUCCESSFUL**; no detectó los dos intercalados corregidos posteriormente |
| JUnit funcional | **15/15**: 3 iluminación + 12 modelo (mensajes, humedad/progreso, límites temporales, datos por ciclo, confirmación, errores y deduplicación de alertas) |
| Instrumentados funcionales en teléfono | **3/3**: transporte local con Paho + navegación/recreación + representación con fixture |
| Plantillas existentes | 1 JUnit y 1 instrumentado también pasan; no se cuentan como cobertura funcional |
| Lint | Sin errores; 8 advertencias conservadas de dependencias más recientes y colores del esqueleto sin uso |
| Primeras ejecuciones instrumentadas | Transporte y representación pasaron; navegación falló en `scrollTo` de Espresso. El botón se alcanzó por gesto real de scroll mediante ADB; se ajustó el test para desplazar antes de pulsar. También se intentó deshabilitar animaciones, sin resolver el fallo; esa configuración se retiró. Las ejecuciones completas posteriores pasaron |
| Broker público desde la PC | Puerto TCP 1883 accesible y handshake MQTT de solo lectura con CONNACK `20-02-00-00`; no se publicaron comandos |
| Broker público desde la app en teléfono | Los intentos observados agotaron el plazo de conexión. La red predeterminada figuraba móvil sin validación de Internet. Permiso INTERNET concedido. No se demuestra la causa exacta ni se declara conectado por la prueba desde la PC; no se alteraron redes ni broker |
| Integración app ↔ firmware IA vía broker público | Pendiente en esa ejecución inicial; no se enviaron comandos al ESP32 ni se validaron sus actuadores entonces. El reporte posterior de integración con Wokwi se registra arriba, sin convertirlo en validación física |
| `git diff --check` | Sin errores de whitespace; avisos de conversión LF/CRLF |

Informes instrumentados: `SistemaDeteccionRopaSecaIA/app/build/reports/androidTests/connected/debug/index.html`. Son resultados locales, no se versionan. Las pruebas de sensor real del incremento 1 siguen siendo evidencia histórica, no una calibración ni una repetición completa del sensor en este incremento.

## Evidencia histórica — incremento 1 (17/10/2026)

Entorno: Windows 11, JDK JetBrains 25.0.3 de Android Studio, Gradle 9.6.0. Rama `android-con-vibecoding`; sin commits, push ni cambio de rama.

| Comprobación | Resultado |
| --- | --- |
| `git status --short --branch` antes de editar | Rama autorizada, árbol limpio |
| `:app:assembleDebug` del esqueleto | Primer intento bloqueado por Java ausente en PATH/JAVA_HOME; repetido con JAVA_HOME temporal: correcto, antes de cambiar funcionalidad |
| `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest` (invocación conjunta, repetida tras ajuste final de recursos) | BUILD SUCCESSFUL |
| `IluminacionTest` | 3 tests, 0 errores/fallos: valores representativos, límites exactos y flotantes inmediatamente anteriores/posteriores a 100/500/10.000, negativos/NaN/infinitos |
| Tests de plantilla existentes | Se preservan; no se consideran cobertura funcional |
| Lint | Sin errores; advertencias de versiones más nuevas de 6 dependencias del esqueleto y de 2 colores de plantilla sin uso; no se actualizaron ni eliminaron por este motivo |
| `gradlew.bat --version` | Confirma Gradle 9.6.0 y launcher JVM 25.0.3 |
| `adb devices -l` en la verificación inicial | Un teléfono `unauthorized`; ningún emulador conectado listado. Esa ejecución no permitió instalar ni ejecutar la app; la revisión ADB posterior se registra abajo |
| `git diff --check` | Sin errores de whitespace; Git avisa sobre conversión LF/CRLF en archivos existentes |

La verificación inicial del implementador no incluyó ejecución en dispositivo. La revisión externa posterior sí comprobó navegación, lifecycle y lectura del sensor real, con el alcance descrito abajo. Los tests JUnit validan clasificación, no hardware, precisión de lux ni callbacks del sistema. `AGENTS.md` se corrigió localmente según lo solicitado; permanece ignorado por la regla existente, sin cambiar `.gitignore` ni forzar su incorporación.

### Revisión ADB posterior — reporte de otro agente transmitido por el usuario

Entorno reportado: **Moto G8 Play, Android 10/API 29**. El agente revisor comprobó que el APK instalado coincidía por SHA-256 con el generado localmente por Android Studio. No se dispone aquí del hash ni se inventa uno. Estos resultados corresponden al APK anterior a la corrección de recorte; no son una repetición realizada por el implementador.

- Sin defectos de severidad alta o media. Sensor real: aproximadamente 265–266 lux y «Poca iluminación».
- Listener: una conexión activa en Condiciones, ninguna al salir o minimizar; reanudación, rotación y cambio de fuente sin duplicaciones.
- Monitoreo: estado desconocido, humedad/progreso sin datos ficticios, barra oculta y control deshabilitado, también tras rotación y restauración después de terminar el proceso en segundo plano sin borrar datos.
- Navegación por las cuatro Activities, regreso por botón y Atrás correctos. En Condiciones horizontal con fuente al 200 % se alcanzó el botón de regreso.
- Sin registros de crash de la app en el búfer disponible; no demuestra ausencia absoluta de fallos.
- Defecto visual bajo: al desplazar Conexión (texto normal) y Condiciones (horizontal, fuente al 200 %), parte del texto se dibujaba detrás de la barra de estado. Causa: `clipToPadding="false"` permitía invadir el padding aplicado por los insets. Se cambió a `true` en los cuatro layouts, sin modificar `Pantalla.java` ni reestructurar las pantallas. **Resuelto según la evaluación posterior comunicada por el usuario**, sin regresiones observadas en esas pruebas; no se infiere una matriz adicional no reportada.
- Defecto documental bajo: se actualizó la afirmación de que la app no había sido instalada/ejecutada, conservando el intento inicial como evidencia histórica.

No se reportaron pruebas de ausencia de sensor, fallo de registro, límites exactos con hardware, bloqueo/desbloqueo, TalkBack, gestos ni modo oscuro. No se infiere calibración del sensor de luz ni validación física del firmware.

Verificación del implementador tras corregir el recorte: `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest`, ejecutados juntos con el mismo JAVA_HOME temporal, terminaron en `BUILD SUCCESSFUL`. `git diff --check` sin errores de whitespace (persisten avisos LF/CRLF). No se instaló ni se probó visualmente el APK corregido en esta ejecución; la revisión externa anterior no acredita esa regresión.

## Pruebas complementarias pendientes del incremento 1

1. Completar tamaños ampliados y recorte en las combinaciones no ensayadas. El defecto de Conexión/Condiciones fue aceptado como resuelto; el reporte integrado agregó Monitoreo horizontal al 200 % con regreso accesible y sin invasión de barras en lo observado, no una aprobación de todas las pantallas/tamaños.
2. Comprobar gestos, bloqueo/desbloqueo y coexistencia con la app manual, no acreditados por el reporte.
3. En Conexión, verificar broker/estación separados y textos del contrato correctos. Sin estación presente no afirmar comunicación, aunque el broker sí esté conectado.
4. En dispositivo o emulador sin sensor, comprobar explicación y navegación operativa; comprobar fallo de registro en un entorno que permita provocarlo. Con sensor virtual, probar 99/100, 499/500 y 9.999/10.000 lux: esto valida UI, no el sensor físico ni su precisión en límites exactos.
5. Completar revisión con pantalla pequeña, tamaño de visualización ampliado, modo oscuro y TalkBack; comprobar legibilidad, recortes y acceso al último botón.

## Aceptación pendiente del incremento 2

El recorrido app ↔ broker ↔ firmware IA en Wokwi ya tiene la evidencia parcial detallada arriba: no queda íntegramente pendiente ni se declara íntegramente aprobado. Para completar la aceptación:

1. Pasar a segundo plano **durante un comando pendiente**, regresar y comprobar resolución sin bloqueo perpetuo, reenvío de órdenes ni finalización automática. No implica agregar recepción/alertas garantizadas en segundo plano.
2. Provocar transiciones de lluvia y ropa seca con **cada Activity en primer plano** y observar durante varios heartbeats que no se repiten avisos. El reporte ya acredita condiciones visibles en las cuatro y un Snackbar de ropa seca en Inicio, no esta matriz completa.
3. Completar los casos de la matriz no acreditados por el reporte: broker conectado sin estado válido de estación (caducidad a los 15 s), pérdida de WiFi del ESP32, reconexión de este sin reinicio y reinicio a espera. No sustituirlos por la caída/recuperación de WiFi del teléfono, que sí fue probada. Registrar explícitamente operación con pulsador físico, inicio directo con ropa húmeda/seca/lluvia y valores límite adicionales cuando se ensayen; no inferirlos de la confirmación genérica de inicio ni de las finalizaciones ya reportadas.
4. Completar ausencia de sensor, fallo de registro y accesibilidad/tamaños no ensayados, según las pruebas complementarias anteriores. Las lecturas de luz y liberación del listener sí tienen evidencia real reportada.
5. Mantener separada la matriz física: montaje y alimentación, calibración de broches/lluvia, seguridad y tipo de buzzer, LED y actuadores reales, y demostración con la app propia. Wokwi y el sensor real del teléfono no validan ese circuito.

No se implementaron segundo plano, notificaciones del sistema, Node-RED, login, historial ni pantalla de configuración. La validación física del firmware conserva los pendientes de su propio README (montaje, calibración y buzzer); este incremento no los aprueba ni los modifica.
