package com.unlam.soa.grupo3.ropaseca;

import android.content.res.ColorStateList;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class CondicionesActivity extends AppCompatActivity implements SensorEventListener {

    private static final float LUZ_MUY_BAJA = 100;
    private static final float LUZ_BAJA = 500;
    private static final float LUZ_BUENA = 10000;

    private SensorManager sensorManager;
    private Sensor sensorLuz;
    private TextView tvIluminacion;
    private TextView tvCondicion;
    private ImageView iconoEstadoSensor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_condiciones);

        tvIluminacion = findViewById(R.id.tvIluminacion);
        tvCondicion = findViewById(R.id.tvCondicion);
        iconoEstadoSensor = findViewById(R.id.iconoEstadoSensor);

        findViewById(R.id.btnVolver).setOnClickListener(v -> finish());

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        sensorLuz = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        if (sensorLuz == null) {
            mostrarSensorNoDisponible();
        } else {
            iconoEstadoSensor.setImageResource(R.drawable.ic_connection_check);

            iconoEstadoSensor.setContentDescription(getString(R.string.condiciones_sensor_disponible));
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

        tvIluminacion.setText(getString(R.string.condiciones_valor_lux, lux));

        actualizarCondicion(lux);
    }

    private void actualizarCondicion(float lux) {
        if (lux < LUZ_MUY_BAJA) {
            mostrarCondicion(R.string.iluminacion_muy_baja, R.color.danger_red, R.color.danger_background);
        } else if (lux < LUZ_BAJA) {
            mostrarCondicion(R.string.iluminacion_baja, R.color.warning_orange, R.color.warning_background);
        } else if (lux < LUZ_BUENA) {
            mostrarCondicion(R.string.iluminacion_buena, R.color.success_green, R.color.success_background);
        } else {
            mostrarCondicion(R.string.iluminacion_excelente, R.color.lavender_dark, R.color.lavender_light);
        }
    }

    private void mostrarCondicion(int textoResId, int colorTextoResId, int colorFondoResId) {
        tvCondicion.setText(textoResId);

        tvCondicion.setTextColor(ContextCompat.getColor(this, colorTextoResId));

        tvCondicion.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, colorFondoResId)));
    }

    private void mostrarSensorNoDisponible() {
        tvIluminacion.setText(R.string.sensor_no_disponible);

        tvIluminacion.setTextSize(22);
        tvCondicion.setText(R.string.sin_sensor_luz);

        tvCondicion.setTextColor(ContextCompat.getColor(this, R.color.danger_red));

        tvCondicion.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.danger_background)));

        iconoEstadoSensor.setImageResource(R.drawable.ic_connection_cross);

        iconoEstadoSensor.setContentDescription(getString(R.string.condiciones_sensor_no_disponible));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No se requiere acción.
    }
}
