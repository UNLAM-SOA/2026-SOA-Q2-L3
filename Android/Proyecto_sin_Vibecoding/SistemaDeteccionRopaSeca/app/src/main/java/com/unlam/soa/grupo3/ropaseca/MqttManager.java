package com.unlam.soa.grupo3.ropaseca;

import android.os.Handler;
import android.os.Looper;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MqttManager {

    private static final String BROKER_URL = "tcp://test.mosquitto.org:1883";

    private static final String TOPIC_CONEXION =
            "unlam/soa/grupo3/lavadero/conexion";
    private static final String TOPIC_ESTADO =
            "unlam/soa/grupo3/lavadero/estado";
    private static final String TOPIC_SENSOR =
            "unlam/soa/grupo3/lavadero/sensor";

    private static final long TIMEOUT_ESTACION_MS = 15000;
    private static final long INTERVALO_VERIFICACION_MS = 5000;

    private static MqttManager instance;

    private MqttAsyncClient mqttClient;

    private boolean estacionConectada = false;
    private String ultimoEstado = null;
    private Integer ultimaHumedad = null;
    private long ultimaComunicacion = 0;

    private final List<MqttListener> listeners = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());

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
                String clientId = "android-grupo3-" + UUID.randomUUID();

                mqttClient = new MqttAsyncClient(
                        BROKER_URL,
                        clientId,
                        null
                );
            }

            MqttConnectOptions opciones = new MqttConnectOptions();
            opciones.setAutomaticReconnect(true);
            opciones.setCleanSession(true);

            mqttClient.connect(opciones, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    System.out.println("MQTT: conectado al broker");

                    notificarBrokerConectado();
                    suscribirseATopics();
                }

                @Override
                public void onFailure(
                        IMqttToken asyncActionToken,
                        Throwable exception
                ) {
                    System.out.println(
                            "MQTT: error de conexión - " + exception.getMessage()
                    );

                    notificarBrokerDesconectado();
                }
            });

        } catch (MqttException e) {
            System.out.println("MQTT: error - " + e.getMessage());
            notificarBrokerDesconectado();
        }
    }

    private void suscribirseATopics() {
        if (!estaConectado()) {
            return;
        }

        suscribirse(TOPIC_CONEXION, this::procesarConexion);
        suscribirse(TOPIC_ESTADO, this::procesarEstado);
        suscribirse(TOPIC_SENSOR, this::procesarSensor);
    }

    private void suscribirse(
            String topic,
            IMqttMessageListener listener
    ) {
        try {
            mqttClient.subscribe(topic, 0, listener);
            System.out.println("MQTT: suscripto a " + topic);

        } catch (MqttException e) {
            System.out.println(
                    "MQTT: error al suscribirse a "
                            + topic
                            + " - "
                            + e.getMessage()
            );
        }
    }

    private void procesarConexion(
            String topic,
            MqttMessage message
    ) {
        String payload = new String(message.getPayload()).trim();

        System.out.println("MQTT conexión: " + payload);

        if ("ONLINE".equalsIgnoreCase(payload)) {
            registrarComunicacion();

            if (!estacionConectada) {
                estacionConectada = true;
                notificarEstacionConectada();
            }
        }
    }

    private void procesarEstado(
            String topic,
            MqttMessage message
    ) {
        String estado = new String(message.getPayload()).trim();

        ultimoEstado = estado;
        registrarComunicacion();
        marcarEstacionConectada();

        System.out.println("MQTT estado: " + estado);

        notificarEstado(estado);
    }

    private void procesarSensor(
            String topic,
            MqttMessage message
    ) {
        String payload = new String(message.getPayload()).trim();

        try {
            int humedad = Integer.parseInt(payload);

            ultimaHumedad = humedad;
            registrarComunicacion();
            marcarEstacionConectada();

            System.out.println("MQTT humedad: " + humedad + "%");

            notificarHumedad(humedad);

        } catch (NumberFormatException e) {
            System.out.println("MQTT: humedad inválida - " + payload);
        }
    }

    private void registrarComunicacion() {
        ultimaComunicacion = System.currentTimeMillis();
    }

    private void marcarEstacionConectada() {
        if (!estacionConectada) {
            estacionConectada = true;
            notificarEstacionConectada();
        }
    }

    private void verificarConexionEstacion() {
        if (!estacionConectada || ultimaComunicacion == 0) {
            return;
        }

        long tiempoSinComunicacion =
                System.currentTimeMillis() - ultimaComunicacion;

        if (tiempoSinComunicacion > TIMEOUT_ESTACION_MS) {
            estacionConectada = false;

            System.out.println("MQTT: estación sin conexión");

            notificarEstacionDesconectada();
        }
    }

    private synchronized void notificarBrokerConectado() {
        for (MqttListener listener : new ArrayList<>(listeners)) {
            listener.onBrokerConectado();
        }
    }

    private synchronized void notificarBrokerDesconectado() {
        for (MqttListener listener : new ArrayList<>(listeners)) {
            listener.onBrokerDesconectado();
        }
    }

    private synchronized void notificarEstacionConectada() {
        for (MqttListener listener : new ArrayList<>(listeners)) {
            listener.onEstacionConectada();
        }
    }

    private synchronized void notificarEstacionDesconectada() {
        for (MqttListener listener : new ArrayList<>(listeners)) {
            listener.onEstacionDesconectada();
        }
    }

    private synchronized void notificarEstado(String estado) {
        for (MqttListener listener : new ArrayList<>(listeners)) {
            listener.onEstadoRecibido(estado);
        }
    }

    private synchronized void notificarHumedad(int humedad) {
        for (MqttListener listener : new ArrayList<>(listeners)) {
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

    public long getUltimaComunicacion() {
        return ultimaComunicacion;
    }
}