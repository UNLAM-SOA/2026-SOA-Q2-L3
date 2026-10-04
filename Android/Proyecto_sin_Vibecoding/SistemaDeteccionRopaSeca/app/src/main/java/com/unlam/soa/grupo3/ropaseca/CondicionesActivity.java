package com.unlam.soa.grupo3.ropaseca;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class CondicionesActivity extends AppCompatActivity implements SensorEventListener {

    private static final float LUZ_MUY_BAJA = 100;
    private static final float LUZ_BAJA = 500;
    private static final float LUZ_BUENA = 10000;

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
            tvIluminacion.setText(R.string.sensor_no_disponible);
            tvCondicion.setText(R.string.sin_sensor_luz);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorLuz != null) {
            sensorManager.registerListener(this, sensorLuz, SensorManager.SENSOR_DELAY_NORMAL);
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
        tvIluminacion.setText(String.format(Locale.getDefault(), "%.0f lux", lux));
        actualizarCondicion(lux);
    }

    private void actualizarCondicion(float lux) {
        if (lux < LUZ_MUY_BAJA) {
            tvCondicion.setText(R.string.iluminacion_muy_baja);
        } else if (lux < LUZ_BAJA) {
            tvCondicion.setText(R.string.iluminacion_baja);
        } else if (lux < LUZ_BUENA) {
            tvCondicion.setText(R.string.iluminacion_buena);
        } else {
            tvCondicion.setText(R.string.iluminacion_excelente);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
