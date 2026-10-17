# Android IA — incremento 1

Base implementada sobre el esqueleto del usuario en `SistemaDeteccionRopaSecaIA/`, con Java, layouts XML y cuatro Activities reales. No implementa MQTT ni controla la estación. No se modificaron firmware, esquema, variante manual, versiones, dependencias ni applicationId (`com.unlam.soa.grupo3.ropaseca.ia`).

**Incremento 1 cerrado por aceptación del usuario.** Tras la corrección mínima de recorte, el usuario confirmó que el defecto observado quedó resuelto sin regresiones en las pruebas realizadas. Esto no aprueba los casos adicionales pendientes ni inicia el incremento 2.

## Uso y decisiones

- **Inicio (`MainActivity`):** nombre «Ropa seca IA», comunicación todavía no disponible y accesos a las otras tres pantallas.
- **Monitoreo:** estado desconocido, humedad/progreso/antigüedad sin datos y condiciones de lluvia/ropa seca sin confirmar. El botón iniciar/finalizar está deshabilitado y no tiene acción. La barra está definida en XML, pero oculta hasta disponer de una lectura válida: no se muestra un 0 % ficticio. Para operar el ESP32 se utiliza su pulsador físico.
- **Condiciones (`CondicionesActivity`):** lectura real de `Sensor.TYPE_LIGHT`, sin permisos especiales ni MQTT. Acercar el teléfono al lugar de la ropa sin cubrir el sensor. Registra el listener en `onResume`, lo libera en `onPause` y borra la lectura al reanudar para esperar una nueva. Explica ausencia de hardware, fallo de registro y lecturas inválidas, sin bloquear las demás pantallas. No estima duración del secado ni modifica el ESP32.
- **Conexión:** separa disponibilidad del broker de comunicación con la estación; ambas se presentan como no comprobadas, no como conectadas. Muestra el contrato existente solo como información.
- Navegación: `Inicio → Monitoreo / Condiciones / Conexión → Inicio`. «Volver a Inicio» finaliza la Activity secundaria; Atrás usa la pila normal de Android. No se crean copias de Inicio para volver.
- `Pantalla.java` comparte únicamente el ajuste de barras del sistema y recortes de pantalla. Cada layout tiene desplazamiento vertical, tamaños de contenido flexibles y estilos comunes compatibles con el tema claro/oscuro del esqueleto. Los cuatro ScrollView conservan `clipToPadding="true"` para que el contenido desplazado no invada los insets. La revisión externa comprobó acceso al botón con fuente al 200 % en Condiciones horizontal; el usuario confirmó posteriormente la resolución del defecto de recorte sin regresiones en las pruebas realizadas.
- `Iluminacion.java` contiene los umbrales ajustables y clasificación, independiente de Android para probarla con JUnit. No se agregaron gestores de conexión, servicios ficticios ni capas para una integración futura.

| Lux válidos | Clasificación |
| --- | --- |
| 0 ≤ lux < 100 | Muy poca iluminación |
| 100 ≤ lux < 500 | Poca iluminación |
| 500 ≤ lux < 10.000 | Buena iluminación |
| lux ≥ 10.000 | Excelente iluminación |

Negativos, NaN e infinitos se rechazan sin clasificación. El valor se muestra sin redondearlo a otro intervalo. La clasificación es orientativa, no una medición del secado.

### Contrato de referencia, sin conexión implementada

Según `Embebido/Proyecto_con_Vibecoding/Configuracion.h` y `ProtocoloMqtt.h`:

- `broker.emqx.io:1883`, MQTT/TCP sin autenticación ni cifrado. Broker público académico; no enviar datos sensibles.
- Prefijo `unlam/soa/grupo3/lavadero-ia/`.
- `comando`: `INICIAR` / `FINALIZAR`.
- `estado`: `ESPERA`, `MONITOREANDO_SECADO`, `ROPA_SECA`, `LLUVIA`.
- `sensor`: humedad general entera de 0 a 100, sin `%`.
- QoS 0, mensajes no retenidos; nunca retener ni reenviar comandos offline.

Estos datos están en los recursos informativos de Conexión; no son una configuración de cliente ejecutable. `ESPERA` aparece como parte del contrato, nunca como un estado recibido. La app no tiene biblioteca MQTT ni permiso de Internet agregado en este incremento.

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
```

Informes locales: `app/build/reports/tests/testDebugUnitTest/index.html` y `app/build/reports/lint-results-debug.html`. APK local: `app/build/outputs/apk/debug/app-debug.apk`; instalar desde Android Studio en un dispositivo autorizado o emulador. No versionar ninguno de esos artefactos, `local.properties` ni secretos. El wrapper y su JAR se conservan.

## Verificación ejecutada — 17/10/2026

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

## Pruebas manuales pendientes

1. Ampliar la comprobación de recorte a Inicio y Monitoreo con contenido ampliado. El defecto observado en Conexión/Condiciones ya fue aceptado como resuelto; no se exige repetirlo para cerrar este incremento.
2. Comprobar gestos, bloqueo/desbloqueo y coexistencia con la app manual, no acreditados por el reporte.
3. En Conexión, verificar broker/estación separados, ningún estado conectado y textos del contrato correctos.
4. En dispositivo o emulador sin sensor, comprobar explicación y navegación operativa; comprobar fallo de registro en un entorno que permita provocarlo. Con sensor virtual, probar 99/100, 499/500 y 9.999/10.000 lux: esto valida UI, no el sensor físico ni su precisión en límites exactos.
5. Completar revisión con pantalla pequeña, tamaño de visualización ampliado, modo oscuro y TalkBack; comprobar legibilidad, recortes y acceso al último botón.

## Pendiente: incremento 2 (requiere autorización)

- Cliente MQTT real y configuración centralizada según el contrato, clientId Android único, reconexión y suscripciones sin duplicación por navegación.
- Validación estricta de mensajes; broker y presencia de estación independientes; antigüedad separada de estado/humedad con reloj monotónico y timeout de estación.
- Inicio/finalización confirmados por estado observado, no por envío; timeout y resolución coherentes al navegar, sin retener comandos.
- Humedad vigente y progreso alineado con firmware: `limitar((100 - H) * 100 / 80, 0, 100)` con truncado entero; no reutilizar lecturas del ciclo anterior.
- Condiciones y avisos vigentes con app visible, sin repetir alertas por heartbeat; pruebas de integración, desconexión, reconexión y reinicio con firmware IA.

No se implementaron segundo plano, notificaciones del sistema, Node-RED, login, historial ni pantalla de configuración. La validación física del firmware conserva los pendientes de su propio README (montaje, calibración y buzzer); este incremento no los aprueba ni los modifica.
