package com.unlam.soa.grupo3.ropaseca;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MonitoreoActivity extends AppCompatActivity {

    private static final String ESTADO_ESPERA = "ESPERA";
    private static final String ESTADO_MONITOREANDO = "MONITOREANDO_SECADO";
    private static final String ESTADO_ROPA_SECA = "ROPA_SECA";
    private static final String ESTADO_LLUVIA = "LLUVIA";
    private static final long INTERVALO_ACTUALIZACION_MS = 1000;

    private TextView tvEstado;
    private TextView tvHumedad;
    private TextView tvProgreso;
    private TextView tvUltimaActualizacion;
    private TextView tvEstadoConexion;
    private TextView tvTituloMensajeEstado;
    private TextView tvMensajeEstado;
    private View bloqueDatosSecado;
    private View bloqueMensajeEstado;
    private ProgressBar progressSecado;
    private Button btnCiclo;
    private MqttManager mqttManager;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable actualizadorTiempo = new Runnable() {
        @Override
        public void run() {
            actualizarUltimaActualizacion();
            handler.postDelayed(this, INTERVALO_ACTUALIZACION_MS);
        }
    };

    private final MqttManager.MqttListener mqttListener = new MqttManager.MqttListener() {
        @Override
        public void onBrokerConectado() {
            runOnUiThread(() -> actualizarConexionYBoton());
        }

        @Override
        public void onBrokerDesconectado() {
            runOnUiThread(() -> actualizarConexionYBoton());
        }

        @Override
        public void onEstacionConectada() {
            runOnUiThread(() -> actualizarConexionYBoton());
        }

        @Override
        public void onEstacionDesconectada() {
            runOnUiThread(() -> actualizarConexionYBoton());
        }

        @Override
        public void onEstadoRecibido(String estado) {
            runOnUiThread(() -> {
                actualizarEstado(estado);
                actualizarConexionYBoton();
            });
        }

        @Override
        public void onHumedadRecibida(int humedad) {
            runOnUiThread(() -> {
                actualizarHumedad(humedad);
                actualizarUltimaActualizacion();
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_monitoreo);

        tvEstado = findViewById(R.id.tvEstado);
        tvHumedad = findViewById(R.id.tvHumedad);
        tvProgreso = findViewById(R.id.tvProgreso);
        tvUltimaActualizacion = findViewById(R.id.tvUltimaActualizacion);
        tvEstadoConexion = findViewById(R.id.tvEstadoConexion);
        tvTituloMensajeEstado = findViewById(R.id.tvTituloMensajeEstado);
        tvMensajeEstado = findViewById(R.id.tvMensajeEstado);
        bloqueDatosSecado = findViewById(R.id.bloqueDatosSecado);
        bloqueMensajeEstado = findViewById(R.id.bloqueMensajeEstado);
        progressSecado = findViewById(R.id.progressSecado);
        btnCiclo = findViewById(R.id.btnCiclo);

        findViewById(R.id.btnVolver).setOnClickListener(v -> finish());
        mqttManager = MqttManager.getInstance();
        cargarUltimosDatos();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mqttManager.agregarListener(mqttListener);
        cargarUltimosDatos();
        handler.removeCallbacks(actualizadorTiempo);
        handler.post(actualizadorTiempo);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mqttManager.quitarListener(mqttListener);
        handler.removeCallbacks(actualizadorTiempo);
    }

    private void cargarUltimosDatos() {
        String ultimoEstado = mqttManager.getUltimoEstado();
        Integer ultimaHumedad = mqttManager.getUltimaHumedad();

        if (ultimoEstado != null) {
            actualizarEstado(ultimoEstado);
        } else {
            bloqueDatosSecado.setVisibility(View.GONE);
            mostrarMensajeEspera();
        }

        if (ultimaHumedad != null) {
            actualizarHumedad(ultimaHumedad);
        }

        actualizarConexionYBoton();
        actualizarUltimaActualizacion();
    }

    private void actualizarEstado(String estado) {
        switch (estado) {
            case ESTADO_ESPERA:
                tvEstado.setText(R.string.estado_espera);
                btnCiclo.setText(R.string.iniciar_ciclo);
                bloqueDatosSecado.setVisibility(View.GONE);
                mostrarMensajeEspera();
                break;
            case ESTADO_MONITOREANDO:
                tvEstado.setText(R.string.estado_monitoreando);
                btnCiclo.setText(R.string.finalizar_ciclo);
                bloqueDatosSecado.setVisibility(View.VISIBLE);
                bloqueMensajeEstado.setVisibility(View.GONE);
                break;
            case ESTADO_ROPA_SECA:
                tvEstado.setText(R.string.estado_ropa_seca);
                btnCiclo.setText(R.string.finalizar_ciclo);
                bloqueDatosSecado.setVisibility(View.VISIBLE);
                mostrarMensajeRopaSeca();
                break;
            case ESTADO_LLUVIA:
                tvEstado.setText(R.string.estado_lluvia);
                btnCiclo.setText(R.string.finalizar_ciclo);
                bloqueDatosSecado.setVisibility(View.VISIBLE);
                mostrarMensajeLluvia();
                break;
            default:
                tvEstado.setText(estado);
                bloqueDatosSecado.setVisibility(View.GONE);
                bloqueMensajeEstado.setVisibility(View.GONE);
        }
    }

    private void mostrarMensajeEspera() {
        mostrarMensaje(R.string.espera_titulo, R.string.espera_mensaje);
    }

    private void mostrarMensajeRopaSeca() {
        mostrarMensaje(R.string.ropa_seca_titulo, R.string.ropa_seca_mensaje);
    }

    private void mostrarMensajeLluvia() {
        mostrarMensaje(R.string.lluvia_titulo, R.string.lluvia_mensaje);
    }

    private void mostrarMensaje(int titulo, int mensaje) {
        bloqueMensajeEstado.setVisibility(View.VISIBLE);
        tvTituloMensajeEstado.setText(titulo);
        tvMensajeEstado.setText(mensaje);
    }

    private void actualizarHumedad(int humedad) {
        int humedadLimitada = Math.max(0, Math.min(100, humedad));
        int progreso = 100 - humedadLimitada;

        tvHumedad.setText(getString(R.string.porcentaje, humedadLimitada));
        tvProgreso.setText(getString(R.string.porcentaje, progreso));
        progressSecado.setProgress(progreso);
    }

    private void actualizarConexionYBoton() {
        actualizarEstadoConexion();
        actualizarBoton();
    }

    private void actualizarEstadoConexion() {
        if (!mqttManager.estaConectado()) {
            tvEstadoConexion.setText(R.string.sin_conexion_broker);
            tvEstadoConexion.setVisibility(View.VISIBLE);
        } else if (!mqttManager.estaEstacionConectada()) {
            tvEstadoConexion.setText(R.string.estacion_sin_conexion_alerta);
            tvEstadoConexion.setVisibility(View.VISIBLE);
        } else {
            tvEstadoConexion.setVisibility(View.GONE);
        }
    }

    private void actualizarBoton() {
        boolean habilitado = mqttManager.estaConectado()
                && mqttManager.estaEstacionConectada()
                && mqttManager.getUltimoEstado() != null;
        btnCiclo.setEnabled(habilitado);
        btnCiclo.setAlpha(habilitado ? 1.0f : 0.5f);
    }

    private void actualizarUltimaActualizacion() {
        long ultimaLectura = mqttManager.getUltimaLecturaSensor();
        if (ultimaLectura == 0) {
            tvUltimaActualizacion.setText(R.string.esperando_actualizacion);
            return;
        }

        long segundos = (System.currentTimeMillis() - ultimaLectura) / 1000;
        if (segundos < 5) {
            tvUltimaActualizacion.setText(R.string.actualizacion_ahora);
        } else if (segundos < 60) {
            tvUltimaActualizacion.setText(getString(R.string.actualizacion_segundos, segundos));
        } else {
            tvUltimaActualizacion.setText(getString(R.string.actualizacion_minutos, segundos / 60));
        }
    }
}
