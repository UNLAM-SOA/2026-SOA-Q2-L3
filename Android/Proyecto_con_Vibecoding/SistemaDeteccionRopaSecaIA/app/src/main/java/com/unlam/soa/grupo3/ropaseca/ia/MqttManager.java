package com.unlam.soa.grupo3.ropaseca.ia;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Estado/listeners en main; todas las operaciones del cliente en un único worker.
final class MqttManager {
    final ModeloEstacion modelo = new ModeloEstacion();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService red;
    private final Set<Runnable> listeners = new LinkedHashSet<>();
    private final String clientId = "android-ia-" + UUID.randomUUID();
    private final String uri;
    private volatile long sesion;
    private volatile long envioAutorizado = -1;
    private volatile long publicacionConfirmable = -1;
    // Solo el worker posee el cliente, incluso mientras conecta/suscribe.
    private MqttClient cliente;
    private boolean visible;
    private boolean conectando;
    private long proximoIntento;

    MqttManager() { this(ConfiguracionMqtt.URI); }

    MqttManager(String uri) { this(uri, Executors.newSingleThreadExecutor()); }

    MqttManager(String uri, ExecutorService red) {
        this.uri = uri;
        this.red = red;
    }

    private final Runnable reloj = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            modelo.verificarPlazos(SystemClock.elapsedRealtime());
            if (!modelo.broker && !conectando && SystemClock.elapsedRealtime() >= proximoIntento) conectar();
            notificar();
            main.postDelayed(this, 1000);
        }
    };

    void agregar(Runnable listener) {
        listeners.add(listener);
        listener.run();
    }

    void quitar(Runnable listener) { listeners.remove(listener); }

    void iniciar() {
        if (visible) return;
        visible = true;
        proximoIntento = 0;
        main.post(reloj);
    }

    void detener() {
        visible = false;
        main.removeCallbacks(reloj);
        sesion++;
        conectando = false;
        modelo.desconectar();
        red.execute(this::cerrarCliente);
        notificar();
    }

    private void conectar() {
        conectando = true;
        long intento = ++sesion;
        red.execute(() -> {
            if (intento != sesion) return;
            cerrarCliente();
            try {
                MqttClient nuevo = new MqttClient(uri, clientId, new MemoryPersistence());
                cliente = nuevo;
                nuevo.setTimeToWait((ConfiguracionMqtt.CONEXION_TIMEOUT_S + 1L) * 1000);
                nuevo.setCallback(new MqttCallback() {
                    @Override public void connectionLost(Throwable cause) {
                        main.post(() -> perderConexion(intento));
                    }

                    @Override public void messageArrived(String topic, MqttMessage message) {
                        long publicacion = publicacionConfirmable;
                        long recibido = SystemClock.elapsedRealtime();
                        if (message.getPayload().length > 32) return;
                        byte[] datos = message.getPayload().clone();
                        boolean retenido = message.isRetained();
                        main.post(() -> {
                            if (!visible || intento != sesion) return;
                            modelo.recibir(topic, datos, retenido, recibido, publicacion);
                            notificar();
                        });
                    }

                    @Override public void deliveryComplete(IMqttDeliveryToken token) { }
                });
                MqttConnectOptions opciones = new MqttConnectOptions();
                opciones.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
                opciones.setAutomaticReconnect(false);
                opciones.setCleanSession(true);
                opciones.setConnectionTimeout(ConfiguracionMqtt.CONEXION_TIMEOUT_S);
                opciones.setKeepAliveInterval(ConfiguracionMqtt.KEEPALIVE_S);
                nuevo.connect(opciones);
                if (intento != sesion) {
                    cerrarCliente();
                    return;
                }
                nuevo.setTimeToWait(ConfiguracionMqtt.OPERACION_TIMEOUT_MS);
                IMqttToken subscripcion = nuevo.subscribeWithResponse(
                        new String[]{ConfiguracionMqtt.ESTADO, ConfiguracionMqtt.SENSOR}, new int[]{0, 0});
                int[] otorgados = subscripcion.getGrantedQos();
                if (otorgados == null || otorgados.length != 2) {
                    throw new MqttException(MqttException.REASON_CODE_SUBSCRIBE_FAILED);
                }
                for (int qos : otorgados) {
                    if (qos != 0) throw new MqttException(MqttException.REASON_CODE_SUBSCRIBE_FAILED);
                }
                if (intento != sesion) {
                    cerrarCliente();
                    return;
                }
                main.post(() -> {
                    if (!visible || intento != sesion) return;
                    conectando = false;
                    modelo.conectar();
                    Log.i("MqttIA", "Broker y suscripciones listos: " + clientId);
                    notificar();
                });
            } catch (MqttException e) {
                Log.w("MqttIA", "No se pudo conectar/suscribir", e);
                cerrarCliente();
                main.post(() -> perderConexion(intento));
            }
        });
    }

    private void perderConexion(long intento) {
        if (intento != sesion) return;
        sesion++;
        conectando = false;
        modelo.desconectar();
        proximoIntento = SystemClock.elapsedRealtime() + ConfiguracionMqtt.REINTENTO_MS;
        red.execute(this::cerrarCliente);
        notificar();
    }

    void enviar(ModeloEstacion.Comando comando) {
        long ahora = SystemClock.elapsedRealtime();
        long numero = modelo.solicitar(comando, ahora);
        if (numero < 0) return;
        long envioSesion = sesion;
        long limiteEstado = modelo.ultimoEstado + ConfiguracionMqtt.VIGENCIA_MS;
        envioAutorizado = numero;
        notificar();
        red.execute(() -> {
            MqttClient destino = cliente;
            try {
                // No hay buffer offline ni reconexión del cliente usado por este envío.
                if (envioAutorizado != numero || envioSesion != sesion || destino == null || !destino.isConnected()
                        || SystemClock.elapsedRealtime() >= limiteEstado
                        || SystemClock.elapsedRealtime() - ahora >= ConfiguracionMqtt.MAXIMO_COLA_MS) {
                    main.post(() -> { modelo.falloEnvio(numero); notificar(); });
                    return;
                }
                MqttMessage mensaje = new MqttMessage(comando.name().getBytes(StandardCharsets.US_ASCII));
                mensaje.setQos(0);
                mensaje.setRetained(false);
                destino.publish(ConfiguracionMqtt.COMANDO, mensaje);
                long publicado = SystemClock.elapsedRealtime();
                // Encolar el marcador ANTES de exponer la etiqueta a nuevos callbacks.
                main.post(() -> {
                    if (envioSesion != sesion) return;
                    modelo.publicado(numero, publicado);
                    notificar();
                });
                publicacionConfirmable = numero;
            } catch (MqttException e) {
                Log.w("MqttIA", "Envío no confirmado", e);
                main.post(() -> {
                    modelo.falloEnvio(numero);
                    perderConexion(envioSesion);
                });
            }
        });
    }

    private void notificar() {
        if (modelo.pendiente == null) envioAutorizado = -1;
        for (Runnable listener : new ArrayList<>(listeners)) listener.run();
    }

    private void cerrarCliente() {
        MqttClient anterior = cliente;
        cliente = null;
        if (anterior != null) cerrar(anterior);
    }

    private static void cerrar(MqttClient cliente) {
        try {
            cliente.disconnectForcibly(0, 1000, false);
        } catch (MqttException e) {
            Log.d("MqttIA", "Cliente ya desconectado", e);
        }
        try {
            cliente.close(true);
        } catch (MqttException e) {
            Log.w("MqttIA", "No se pudo cerrar el cliente", e);
        }
    }
}
