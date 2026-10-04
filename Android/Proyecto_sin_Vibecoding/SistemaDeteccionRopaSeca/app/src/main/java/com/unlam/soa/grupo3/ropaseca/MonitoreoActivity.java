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
            handler.postDelayed(this, 1000);
        }
    };

    private final MqttManager.MqttListener mqttListener = new MqttManager.MqttListener() {

        @Override
        public void onBrokerConectado() {
            runOnUiThread(() -> {
                actualizarEstadoConexion();
                actualizarBoton();
            });
        }

        @Override
        public void onBrokerDesconectado() {
            runOnUiThread(() -> {
                actualizarEstadoConexion();
                actualizarBoton();
            });
        }

        @Override
        public void onEstacionConectada() {
            runOnUiThread(() -> {
                actualizarEstadoConexion();
                actualizarBoton();
            });
        }

        @Override
        public void onEstacionDesconectada() {
            runOnUiThread(() -> {
                actualizarEstadoConexion();
                actualizarBoton();
            });
        }

        @Override
        public void onEstadoRecibido(String estado) {
            runOnUiThread(() -> {
                actualizarEstado(estado);
                actualizarEstadoConexion();
                actualizarBoton();
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

        actualizarEstadoConexion();
        actualizarBoton();
        actualizarUltimaActualizacion();
    }

    private void actualizarEstado(String estado) {
        switch (estado) {
            case "ESPERA":
                tvEstado.setText("ESPERA");
                btnCiclo.setText("INICIAR CICLO");

                bloqueDatosSecado.setVisibility(View.GONE);
                mostrarMensajeEspera();
                break;

            case "MONITOREANDO_SECADO":
                tvEstado.setText("MONITOREANDO SECADO");
                btnCiclo.setText("FINALIZAR CICLO");

                bloqueDatosSecado.setVisibility(View.VISIBLE);
                bloqueMensajeEstado.setVisibility(View.GONE);
                break;

            case "ROPA_SECA":
                tvEstado.setText("ROPA SECA");
                btnCiclo.setText("FINALIZAR CICLO");

                bloqueDatosSecado.setVisibility(View.VISIBLE);
                mostrarMensajeRopaSeca();
                break;

            case "LLUVIA":
                tvEstado.setText("LLUVIA DETECTADA");
                btnCiclo.setText("FINALIZAR CICLO");

                bloqueDatosSecado.setVisibility(View.VISIBLE);
                mostrarMensajeLluvia();
                break;

            default:
                tvEstado.setText(estado);

                bloqueDatosSecado.setVisibility(View.GONE);
                bloqueMensajeEstado.setVisibility(View.GONE);
                break;
        }
    }

    private void mostrarMensajeEspera() {
        bloqueMensajeEstado.setVisibility(View.VISIBLE);
        tvTituloMensajeEstado.setText("Todo listo para comenzar");
        tvMensajeEstado.setText(
                "Iniciá un ciclo cuando cuelgues la ropa para comenzar a monitorear el secado."
        );
    }

    private void mostrarMensajeRopaSeca() {
        bloqueMensajeEstado.setVisibility(View.VISIBLE);
        tvTituloMensajeEstado.setText("✓ ¡Tu ropa está seca!");
        tvMensajeEstado.setText(
                "Ya está lista para descolgar, doblar y guardar."
        );
    }

    private void mostrarMensajeLluvia() {
        bloqueMensajeEstado.setVisibility(View.VISIBLE);
        tvTituloMensajeEstado.setText("⚠ ¡Lluvia detectada!");
        tvMensajeEstado.setText(
                "Tu ropa puede mojarse. ¡Descolgala cuanto antes!"
        );
    }

    private void actualizarHumedad(int humedad) {
        int humedadLimitada = Math.max(0, Math.min(100, humedad));
        int progreso = 100 - humedadLimitada;

        tvHumedad.setText(humedadLimitada + " %");
        tvProgreso.setText(progreso + " %");
        progressSecado.setProgress(progreso);
    }

    private void actualizarEstadoConexion() {
        if (!mqttManager.estaConectado()) {
            tvEstadoConexion.setText("⚠ Sin conexión con el broker");
            tvEstadoConexion.setVisibility(View.VISIBLE);
            return;
        }

        if (!mqttManager.estaEstacionConectada()) {
            tvEstadoConexion.setText("⚠ Estación sin conexión");
            tvEstadoConexion.setVisibility(View.VISIBLE);
            return;
        }

        tvEstadoConexion.setVisibility(View.GONE);
    }

    private void actualizarBoton() {
        boolean habilitado =
                mqttManager.estaConectado()
                        && mqttManager.estaEstacionConectada()
                        && mqttManager.getUltimoEstado() != null;

        btnCiclo.setEnabled(habilitado);
        btnCiclo.setAlpha(habilitado ? 1.0f : 0.5f);
    }

    private void actualizarUltimaActualizacion() {
        long ultimaLectura = mqttManager.getUltimaLecturaSensor();

        if (ultimaLectura == 0) {
            tvUltimaActualizacion.setText("Esperando actualización...");
            return;
        }

        long segundos = (System.currentTimeMillis() - ultimaLectura) / 1000;

        if (segundos < 5) {
            tvUltimaActualizacion.setText("Última actualización: ahora");
        } else if (segundos < 60) {
            tvUltimaActualizacion.setText(
                    "Última actualización: hace " + segundos + " segundos"
            );
        } else {
            long minutos = segundos / 60;

            tvUltimaActualizacion.setText(
                    "Última actualización: hace " + minutos + " min"
            );
        }
    }
}