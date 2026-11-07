package com.unlam.soa.grupo3.ropaseca;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/*
SIMULADO DE WOWKI ACTUALIZADO
https://wokwi.com/projects/476992389295348737

ESCUCHAR BROKER
mosquitto_sub -h broker.emqx.io -p 1883 -t "unlam/soa/grupo3/lavadero/#" -v

ENVIAR ESTADO
while true; do mosquitto_pub -h broker.emqx.io -p 1883 -t "unlam/soa/grupo3/lavadero/estado" -m "{estado}"; sleep 5; done

ENVIAR SENSOR
mosquitto_pub -h broker.emqx.io -p 1883 -t "unlam/soa/grupo3/lavadero/sensor" -m "{nivel_de_humedad}"
 */

public class MainActivity extends AppCompatActivity {

    private MqttManager mqttManager;
    private TextView tvEstadoConexion;

    private final MqttManager.MqttListener mqttListener = new MqttManager.MqttListener() {
        @Override
        public void onBrokerConectado() {
        }

        @Override
        public void onBrokerDesconectado() {
            runOnUiThread(() -> mostrarEstacionDesconectada());
        }

        @Override
        public void onEstacionConectada() {
            runOnUiThread(() -> mostrarEstacionConectada());
        }

        @Override
        public void onEstacionDesconectada() {
            runOnUiThread(() -> mostrarEstacionDesconectada());
        }

        @Override
        public void onEstadoRecibido(String estado) {
        }

        @Override
        public void onHumedadRecibida(int humedad) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvEstadoConexion = findViewById(R.id.tvEstadoConexion);
        Button btnMonitorear = findViewById(R.id.btnMonitorear);
        Button btnCondiciones = findViewById(R.id.btnCondiciones);
        Button btnConexion = findViewById(R.id.btnConexion);

        mqttManager = MqttManager.getInstance();
        mqttManager.conectar();
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }

        btnMonitorear.setOnClickListener(v -> abrirActivity(MonitoreoActivity.class));
        btnCondiciones.setOnClickListener(v -> abrirActivity(CondicionesActivity.class));
        btnConexion.setOnClickListener(v -> abrirActivity(ConexionActivity.class));
    }

    @Override
    protected void onStart() {
        super.onStart();
        mqttManager.agregarListener(mqttListener);
        actualizarEstadoEstacion();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mqttManager.quitarListener(mqttListener);
    }

    private void actualizarEstadoEstacion() {
        if (mqttManager.estaEstacionConectada()) {
            mostrarEstacionConectada();
        } else {
            mostrarEstacionDesconectada();
        }
    }

    private void mostrarEstacionConectada() {
        tvEstadoConexion.setText(R.string.estacion_conectada);
    }

    private void mostrarEstacionDesconectada() {
        tvEstadoConexion.setText(R.string.estacion_sin_conexion);
    }

    private void abrirActivity(Class<?> activity) {
        startActivity(new Intent(this, activity));
    }
}
