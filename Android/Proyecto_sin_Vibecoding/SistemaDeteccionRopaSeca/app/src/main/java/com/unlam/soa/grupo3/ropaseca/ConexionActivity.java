package com.unlam.soa.grupo3.ropaseca;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class ConexionActivity extends AppCompatActivity {

    private TextView tvBrokerCard;
    private TextView tvEstacionCard;
    private TextView tvUltimaComunicacion;

    private MqttManager mqttManager;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable actualizadorTiempo = new Runnable() {
        @Override
        public void run() {
            actualizarUltimaComunicacion();
            handler.postDelayed(this, 1000);
        }
    };

    private final MqttManager.MqttListener mqttListener = new MqttManager.MqttListener() {

        @Override
        public void onBrokerConectado() {
            runOnUiThread(() -> mostrarBrokerConectado());
        }

        @Override
        public void onBrokerDesconectado() {
            runOnUiThread(() -> mostrarBrokerDesconectado());
        }

        @Override
        public void onEstacionConectada() {
            runOnUiThread(() -> {
                mostrarEstacionConectada();
                actualizarUltimaComunicacion();
            });
        }

        @Override
        public void onEstacionDesconectada() {
            runOnUiThread(() -> {
                mostrarEstacionDesconectada();
                actualizarUltimaComunicacion();
            });
        }

        @Override
        public void onEstadoRecibido(String estado) {
            runOnUiThread(() -> {
                mostrarEstacionConectada();
                actualizarUltimaComunicacion();
            });
        }

        @Override
        public void onHumedadRecibida(int humedad) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conexion);

        tvBrokerCard = findViewById(R.id.tvBrokerCard);
        tvEstacionCard = findViewById(R.id.tvEstacionCard);
        tvUltimaComunicacion = findViewById(R.id.tvUltimaComunicacion);

        findViewById(R.id.btnVolver).setOnClickListener(v -> finish());

        mqttManager = MqttManager.getInstance();

        actualizarPantalla();
    }

    @Override
    protected void onStart() {
        super.onStart();

        mqttManager.agregarListener(mqttListener);
        actualizarPantalla();

        handler.removeCallbacks(actualizadorTiempo);
        handler.post(actualizadorTiempo);
    }

    @Override
    protected void onStop() {
        super.onStop();

        mqttManager.quitarListener(mqttListener);
        handler.removeCallbacks(actualizadorTiempo);
    }

    private void actualizarPantalla() {
        if (mqttManager.estaConectado()) {
            mostrarBrokerConectado();
        } else {
            mostrarBrokerDesconectado();
        }

        if (mqttManager.estaEstacionConectada()) {
            mostrarEstacionConectada();
        } else {
            mostrarEstacionDesconectada();
        }

        actualizarUltimaComunicacion();
    }

    private void mostrarBrokerConectado() {
        tvBrokerCard.setText("BROKER MQTT\n\n● Conectado");
    }

    private void mostrarBrokerDesconectado() {
        tvBrokerCard.setText("BROKER MQTT\n\n● Desconectado");
    }

    private void mostrarEstacionConectada() {
        tvEstacionCard.setText("ESTACIÓN DE SECADO\n\n● Conectada");
    }

    private void mostrarEstacionDesconectada() {
        tvEstacionCard.setText("ESTACIÓN DE SECADO\n\n● Sin conexión");
    }

    private void actualizarUltimaComunicacion() {
        long ultimoEstado = mqttManager.getUltimoEstadoRecibido();

        if (ultimoEstado == 0) {
            tvUltimaComunicacion.setText("Sin comunicaciones registradas");
            return;
        }

        long segundos = (System.currentTimeMillis() - ultimoEstado) / 1000;

        if (segundos < 5) {
            tvUltimaComunicacion.setText("Última comunicación: ahora");
        } else if (segundos < 60) {
            tvUltimaComunicacion.setText(
                    "Última comunicación: hace " + segundos + " segundos"
            );
        } else {
            long minutos = segundos / 60;

            tvUltimaComunicacion.setText(
                    "Última comunicación: hace " + minutos + " min"
            );
        }
    }
}