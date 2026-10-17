package com.unlam.soa.grupo3.ropaseca.ia;

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
    private TextView lectura;
    private TextView clasificacion;
    private boolean escuchando;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_condiciones);
        Pantalla.ajustarBarras(this);
        findViewById(R.id.btnVolver).setOnClickListener(v -> finish());
        lectura = findViewById(R.id.tvLux);
        clasificacion = findViewById(R.id.tvClasificacion);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensorLuz = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        lectura.setText(R.string.lux_sin_datos);
        if (sensorLuz == null) {
            clasificacion.setText(R.string.sin_sensor);
            return;
        }
        clasificacion.setText(R.string.esperando_luz);
        escuchando = sensorManager.registerListener(this, sensorLuz, SensorManager.SENSOR_DELAY_NORMAL);
        if (!escuchando) clasificacion.setText(R.string.error_sensor);
    }

    @Override
    protected void onPause() {
        escuchando = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!escuchando || event.sensor.getType() != Sensor.TYPE_LIGHT || event.values.length == 0) return;
        float lux = event.values[0];
        Iluminacion.Nivel nivel = Iluminacion.clasificar(lux);
        if (nivel == Iluminacion.Nivel.SIN_DATOS) {
            lectura.setText(R.string.lux_sin_datos);
            clasificacion.setText(R.string.lectura_invalida);
            return;
        }
        // Mostrar el valor sin redondearlo a otro intervalo de clasificación.
        lectura.setText(getString(R.string.lux_valor, Float.toString(lux)));
        switch (nivel) {
            case MUY_POCA: clasificacion.setText(R.string.luz_muy_poca); break;
            case POCA: clasificacion.setText(R.string.luz_poca); break;
            case BUENA: clasificacion.setText(R.string.luz_buena); break;
            case EXCELENTE: clasificacion.setText(R.string.luz_excelente); break;
            default: clasificacion.setText(R.string.lectura_invalida);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
