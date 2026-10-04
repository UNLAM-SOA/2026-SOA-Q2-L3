package com.unlam.soa.grupo3.ropaseca;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class ConexionActivity extends AppCompatActivity {

    private TextView tvBrokerCard;
    private TextView tvEstacionCard;
    private TextView tvUltimaComunicacion;

    private MqttManager mqttManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conexion);

        tvBrokerCard = findViewById(R.id.tvBrokerCard);
        tvEstacionCard = findViewById(R.id.tvEstacionCard);
        tvUltimaComunicacion =
                findViewById(R.id.tvUltimaComunicacion);

        findViewById(R.id.btnVolver)
                .setOnClickListener(v -> finish());

        mqttManager = MqttManager.getInstance();

        configurarListener();
        actualizarEstadoBroker();
    }

    private void configurarListener() {

        mqttManager.setConexionListener(
                new MqttManager.ConexionListener() {

                    @Override
                    public void onConectado() {
                        runOnUiThread(() ->
                                mostrarBrokerConectado()
                        );
                    }

                    @Override
                    public void onError(String mensaje) {
                        runOnUiThread(() ->
                                mostrarBrokerDesconectado()
                        );
                    }
                }
        );
    }

    private void actualizarEstadoBroker() {

        if (mqttManager.estaConectado()) {
            mostrarBrokerConectado();
        } else {
            mostrarBrokerDesconectado();
        }
    }

    private void mostrarBrokerConectado() {

        tvBrokerCard.setText(
                "BROKER MQTT\n\n● Conectado"
        );

        tvUltimaComunicacion.setText(
                "Conexión con el broker establecida"
        );
    }

    private void mostrarBrokerDesconectado() {

        tvBrokerCard.setText(
                "BROKER MQTT\n\n● Desconectado"
        );

        tvUltimaComunicacion.setText(
                "Sin conexión con el broker"
        );
    }
}