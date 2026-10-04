package com.unlam.soa.grupo3.ropaseca;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MqttManager {

    private static final String TAG = "MqttManager";
    private static final String BROKER_URL = "tcp://broker.emqx.io:1883";
    private static final String TOPIC_ESTADO = "unlam/soa/grupo3/lavadero/estado";
    private static final String TOPIC_SENSOR = "unlam/soa/grupo3/lavadero/sensor";
    private static final String TOPIC_COMANDO = "unlam/soa/grupo3/lavadero/comando";

    public static final String COMANDO_INICIAR = "INICIAR";
    public static final String COMANDO_FINALIZAR = "FINALIZAR";

    private static final long TIMEOUT_ESTACION_MS = 15000;
    private static final long INTERVALO_VERIFICACION_MS = 5000;

    private static MqttManager instance;

    private final List<MqttListener> listeners = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private MqttAsyncClient mqttClient;
    private volatile boolean estacionConectada;
    private volatile String ultimoEstado;
    private volatile Integer ultimaHumedad;
    private volatile long ultimoEstadoRecibido;
    private volatile long ultimaLecturaSensor;

    private final Runnable verificadorConexion = new Runnable() {
        @Override
        public void run() {
            verificarConexionEstacion();
            handler.postDelayed(this, INTERVALO_VERIFICACION_MS);
        }
    };

    private MqttManager() {
        handler.post(verificadorConexion);
    }

    public static synchronized MqttManager getInstance() {
        if (instance == null) {
            instance = new MqttManager();
        }
        return instance;
    }

    public interface MqttListener {
        void onBrokerConectado();
        void onBrokerDesconectado();
        void onEstacionConectada();
        void onEstacionDesconectada();
        void onEstadoRecibido(String estado);
        void onHumedadRecibida(int humedad);
    }

    public synchronized void agregarListener(MqttListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public synchronized void quitarListener(MqttListener listener) {
        listeners.remove(listener);
    }

    public synchronized void conectar() {
        if (estaConectado()) {
            notificarBrokerConectado();
            return;
        }

        try {
            if (mqttClient == null) {
                crearCliente();
            }

            MqttConnectOptions opciones = new MqttConnectOptions();
            opciones.setAutomaticReconnect(true);
            opciones.setCleanSession(true);

            mqttClient.connect(opciones, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    Log.d(TAG, "Conectado al broker");
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(TAG, "Error de conexión", exception);
                    notificarBrokerDesconectado();
                }
            });
        } catch (MqttException e) {
            Log.e(TAG, "Error al conectar", e);
            notificarBrokerDesconectado();
        }
    }

    private void crearCliente() throws MqttException {
        String clientId = "android-grupo3-" + UUID.randomUUID();
        mqttClient = new MqttAsyncClient(BROKER_URL, clientId, null);
        mqttClient.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                Log.d(TAG, reconnect ? "Reconectado al broker" : "Conexión MQTT lista");
                notificarBrokerConectado();
                suscribirseATopics();
            }

            @Override
            public void connectionLost(Throwable cause) {
                Log.w(TAG, "Conexión con el broker perdida", cause);
                marcarEstacionDesconectada();
                notificarBrokerDesconectado();
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        });
    }

    private void suscribirseATopics() {
        if (!estaConectado()) {
            return;
        }
        suscribirse(TOPIC_ESTADO, this::procesarEstado);
        suscribirse(TOPIC_SENSOR, this::procesarSensor);
    }

    private void suscribirse(String topic, IMqttMessageListener listener) {
        try {
            mqttClient.subscribe(topic, 0, listener);
            Log.d(TAG, "Suscripto a " + topic);
        } catch (MqttException e) {
            Log.e(TAG, "Error al suscribirse a " + topic, e);
        }
    }

    private void procesarEstado(String topic, MqttMessage message) {
        String estado = new String(message.getPayload(), StandardCharsets.UTF_8).trim();
        ultimoEstado = estado;
        ultimoEstadoRecibido = System.currentTimeMillis();

        if (!estacionConectada) {
            estacionConectada = true;
            notificarEstacionConectada();
        }
        notificarEstado(estado);
    }

    private void procesarSensor(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8).trim();
        try {
            int humedad = Integer.parseInt(payload);
            ultimaHumedad = humedad;
            ultimaLecturaSensor = System.currentTimeMillis();
            notificarHumedad(humedad);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Humedad inválida: " + payload);
        }
    }

    public boolean enviarComando(String comando) {
        if (!estaConectado() || !estacionConectada) {
            return false;
        }

        try {
            MqttMessage mensaje = new MqttMessage(
                    comando.getBytes(StandardCharsets.UTF_8)
            );
            mensaje.setQos(0);

            mqttClient.publish(TOPIC_COMANDO, mensaje);

            Log.d(TAG, "Comando enviado: " + comando);
            return true;
        } catch (MqttException e) {
            Log.e(TAG, "Error al enviar comando: " + comando, e);
            return false;
        }
    }

    private void verificarConexionEstacion() {
        if (!estacionConectada || ultimoEstadoRecibido == 0) {
            return;
        }

        long tiempoSinEstado = System.currentTimeMillis() - ultimoEstadoRecibido;
        if (tiempoSinEstado > TIMEOUT_ESTACION_MS) {
            marcarEstacionDesconectada();
        }
    }

    private void marcarEstacionDesconectada() {
        if (estacionConectada) {
            estacionConectada = false;
            notificarEstacionDesconectada();
        }
    }

    private synchronized List<MqttListener> obtenerListeners() {
        return new ArrayList<>(listeners);
    }

    private void notificarBrokerConectado() {
        for (MqttListener listener : obtenerListeners()) {
            listener.onBrokerConectado();
        }
    }

    private void notificarBrokerDesconectado() {
        for (MqttListener listener : obtenerListeners()) {
            listener.onBrokerDesconectado();
        }
    }

    private void notificarEstacionConectada() {
        for (MqttListener listener : obtenerListeners()) {
            listener.onEstacionConectada();
        }
    }

    private void notificarEstacionDesconectada() {
        for (MqttListener listener : obtenerListeners()) {
            listener.onEstacionDesconectada();
        }
    }

    private void notificarEstado(String estado) {
        for (MqttListener listener : obtenerListeners()) {
            listener.onEstadoRecibido(estado);
        }
    }

    private void notificarHumedad(int humedad) {
        for (MqttListener listener : obtenerListeners()) {
            listener.onHumedadRecibida(humedad);
        }
    }

    public boolean estaConectado() {
        return mqttClient != null && mqttClient.isConnected();
    }

    public boolean estaEstacionConectada() {
        return estacionConectada;
    }

    public String getUltimoEstado() {
        return ultimoEstado;
    }

    public Integer getUltimaHumedad() {
        return ultimaHumedad;
    }

    public long getUltimoEstadoRecibido() {
        return ultimoEstadoRecibido;
    }

    public long getUltimaLecturaSensor() {
        return ultimaLecturaSensor;
    }
}
