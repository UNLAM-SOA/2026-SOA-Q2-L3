package com.unlam.soa.grupo3.ropaseca;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class CondicionesActivity extends AppCompatActivity implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor sensorLuz;

    private TextView tvIluminacion;
    private TextView tvCondicion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_condiciones);

        tvIluminacion = findViewById(R.id.tvIluminacion);
        tvCondicion = findViewById(R.id.tvCondicion);

        findViewById(R.id.btnVolver).setOnClickListener(v -> finish());

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensorLuz = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        if (sensorLuz == null) {
            tvIluminacion.setText("No disponible");
            tvCondicion.setText("Este dispositivo no tiene sensor de luz");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (sensorLuz != null) {
            sensorManager.registerListener(
                    this,
                    sensorLuz,
                    SensorManager.SENSOR_DELAY_NORMAL
            );
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (sensorLuz != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_LIGHT) {
            return;
        }

        float lux = event.values[0];

        tvIluminacion.setText(String.format("%.0f lux", lux));
        actualizarCondicion(lux);
    }

    private void actualizarCondicion(float lux) {
        if (lux < 100) {
            tvCondicion.setText("☁  MUY POCA ILUMINACIÓN");
        } else if (lux < 500) {
            tvCondicion.setText("☁  POCA ILUMINACIÓN");
        } else if (lux < 10000) {
            tvCondicion.setText("☀  BUENA ILUMINACIÓN");
        } else {
            tvCondicion.setText("☀  EXCELENTE ILUMINACIÓN");
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No necesitamos reaccionar a cambios de precisión.
    }
}