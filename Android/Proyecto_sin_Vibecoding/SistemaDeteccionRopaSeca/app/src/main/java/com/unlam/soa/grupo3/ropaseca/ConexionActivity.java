package com.unlam.soa.grupo3.ropaseca;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class ConexionActivity extends AppCompatActivity {

    private static final long INTERVALO_ACTUALIZACION_MS = 1000;

    private TextView tvBrokerCard;
    private TextView tvEstacionCard;
    private TextView tvUltimaComunicacion;

    private ImageView iconoBroker;
    private ImageView iconoEstacion;

    private MqttManager mqttManager;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable actualizadorTiempo = new Runnable() {
        @Override
        public void run() {
            actualizarUltimaComunicacion();
            handler.postDelayed(this, INTERVALO_ACTUALIZACION_MS);
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
            runOnUiThread(() -> actualizarEstacion(true));
        }

        @Override
        public void onEstacionDesconectada() {
            runOnUiThread(() -> actualizarEstacion(false));
        }

        @Override
        public void onEstadoRecibido(String estado) {
            runOnUiThread(() -> actualizarEstacion(true));
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

        iconoBroker = findViewById(R.id.iconoBroker);
        iconoEstacion = findViewById(R.id.iconoEstacion);

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

        actualizarEstacion(mqttManager.estaEstacionConectada());
    }

    private void actualizarEstacion(boolean conectada) {
        actualizarIndicador(tvEstacionCard, iconoEstacion, conectada ? R.string.conexion_estacion_conectada_badge : R.string.conexion_estacion_desconectada_badge, conectada);

        actualizarUltimaComunicacion();
    }

    private void mostrarBrokerConectado() {
        actualizarIndicador(tvBrokerCard, iconoBroker, R.string.conexion_broker_conectado_badge, true);
    }

    private void mostrarBrokerDesconectado() {
        actualizarIndicador(tvBrokerCard, iconoBroker, R.string.conexion_broker_desconectado_badge, false);
    }

    private void actualizarIndicador(TextView etiqueta, ImageView icono, int textoResId, boolean conectado) {
        int colorTexto = ContextCompat.getColor(this, conectado ? R.color.success_green : R.color.warning_orange);

        int colorFondo = ContextCompat.getColor(this, conectado ? R.color.success_background : R.color.warning_background);

        etiqueta.setText(textoResId);
        etiqueta.setTextColor(colorTexto);

        etiqueta.setBackgroundTintList(ColorStateList.valueOf(colorFondo));

        icono.setImageResource(conectado ? R.drawable.ic_connection_check : R.drawable.ic_connection_cross);
    }

    private void actualizarUltimaComunicacion() {
        long ultimoEstado = mqttManager.getUltimoEstadoRecibido();

        if (ultimoEstado == 0) {
            tvUltimaComunicacion.setText(R.string.sin_comunicaciones);
            return;
        }

        long segundos = (System.currentTimeMillis() - ultimoEstado) / 1000;

        if (segundos < 5) {
            tvUltimaComunicacion.setText(R.string.comunicacion_ahora);
        } else if (segundos < 60) {
            tvUltimaComunicacion.setText(getString(R.string.comunicacion_segundos, segundos));
        } else {
            tvUltimaComunicacion.setText(getString(R.string.comunicacion_minutos, segundos / 60));
        }
    }
}
