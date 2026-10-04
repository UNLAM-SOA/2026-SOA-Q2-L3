package com.unlam.soa.grupo3.ropaseca;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private MqttManager mqttManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mqttManager = MqttManager.getInstance();

        mqttManager.conectar();

        Button btnMonitorear = findViewById(R.id.btnMonitorear);
        Button btnCondiciones = findViewById(R.id.btnCondiciones);
        Button btnConexion = findViewById(R.id.btnConexion);

        btnMonitorear.setOnClickListener(v ->
                abrirActivity(MonitoreoActivity.class));

        btnCondiciones.setOnClickListener(v ->
                abrirActivity(CondicionesActivity.class));

        btnConexion.setOnClickListener(v ->
                abrirActivity(ConexionActivity.class));
    }

    private void abrirActivity(Class<?> activity) {
        Intent intent = new Intent(this, activity);
        startActivity(intent);
    }
}