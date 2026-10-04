package com.unlam.soa.grupo3.ropaseca;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.util.UUID;

public class MqttManager {

    private static final String BROKER_URL =
            "tcp://test.mosquitto.org:1883";

    // Única instancia de MqttManager para toda la aplicación
    private static MqttManager instance;

    private MqttAsyncClient mqttClient;
    private ConexionListener conexionListener;

    private MqttManager() {
        // Constructor privado para evitar crear múltiples instancias
    }

    public static synchronized MqttManager getInstance() {
        if (instance == null) {
            instance = new MqttManager();
        }

        return instance;
    }

    public interface ConexionListener {
        void onConectado();
        void onError(String mensaje);
    }

    public void setConexionListener(ConexionListener listener) {
        this.conexionListener = listener;
    }

    public void conectar() {

        // Si ya estamos conectados, no hacemos otra conexión
        if (estaConectado()) {
            if (conexionListener != null) {
                conexionListener.onConectado();
            }
            return;
        }

        try {

            // Si todavía no existe el cliente MQTT, lo creamos
            if (mqttClient == null) {

                String clientId =
                        "android-grupo3-" + UUID.randomUUID();

                mqttClient = new MqttAsyncClient(
                        BROKER_URL,
                        clientId,
                        null
                );
            }

            MqttConnectOptions opciones =
                    new MqttConnectOptions();

            opciones.setAutomaticReconnect(true);
            opciones.setCleanSession(true);

            mqttClient.connect(
                    opciones,
                    null,
                    new IMqttActionListener() {

                        @Override
                        public void onSuccess(
                                IMqttToken asyncActionToken
                        ) {
                            System.out.println(
                                    "MQTT: conectado al broker"
                            );

                            if (conexionListener != null) {
                                conexionListener.onConectado();
                            }
                        }

                        @Override
                        public void onFailure(
                                IMqttToken asyncActionToken,
                                Throwable exception
                        ) {
                            System.out.println(
                                    "MQTT: error de conexión - "
                                            + exception.getMessage()
                            );

                            if (conexionListener != null) {
                                conexionListener.onError(
                                        exception.getMessage()
                                );
                            }
                        }
                    }
            );

        } catch (MqttException e) {

            System.out.println(
                    "MQTT: error al crear/conectar el cliente - "
                            + e.getMessage()
            );

            if (conexionListener != null) {
                conexionListener.onError(
                        e.getMessage()
                );
            }
        }
    }

    public boolean estaConectado() {
        return mqttClient != null
                && mqttClient.isConnected();
    }
}