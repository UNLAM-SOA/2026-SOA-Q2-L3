package com.unlam.soa.grupo3.ropaseca;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MonitoreoActivity extends AppCompatActivity {

    private static final String ESTADO_ESPERA = "ESPERA";
    private static final String ESTADO_MONITOREANDO = "MONITOREANDO_SECADO";
    private static final String ESTADO_ROPA_SECA = "ROPA_SECA";
    private static final String ESTADO_LLUVIA = "LLUVIA";

    private static final long INTERVALO_ACTUALIZACION_MS = 1000;
    private static final long TIMEOUT_COMANDO_MS = 2000;

    private TextView tvEstado;
    private TextView tvHumedad;
    private TextView tvProgreso;
    private TextView tvUltimaActualizacion;
    private TextView tvEstadoConexion;
    private TextView tvTituloMensajeEstado;
    private TextView tvMensajeEstado;

    private ImageView iconoEstadoConexion;

    private View bloqueDatosSecado;
    private View bloqueMensajeEstado;

    private CircularProgressView progressSecado;
    private Button btnCiclo;

    private MqttManager mqttManager;

    private boolean comandoPendiente;
    private String estadoEsperado;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable actualizadorTiempo = new Runnable() {
        @Override
        public void run() {
            actualizarUltimaActualizacion();
            handler.postDelayed(this, INTERVALO_ACTUALIZACION_MS);
        }
    };

    private final Runnable timeoutComando = () -> {
        if (!comandoPendiente) {
            return;
        }

        boolean eraFinalizacion = ESTADO_ESPERA.equals(estadoEsperado);

        comandoPendiente = false;
        estadoEsperado = null;

        actualizarBoton();

        if (eraFinalizacion) {
            mostrarErrorFinalizacion();
        } else {
            Toast.makeText(this, R.string.error_iniciar_ciclo, Toast.LENGTH_LONG).show();
        }
    };

    private final MqttManager.MqttListener mqttListener = new MqttManager.MqttListener() {

        @Override
        public void onBrokerConectado() {
            runOnUiThread(() -> actualizarConexionYBoton());
        }

        @Override
        public void onBrokerDesconectado() {
            runOnUiThread(() -> {
                cancelarComandoPendiente();
                actualizarConexionYBoton();
                avisarFinalizacionManualSiCorresponde();
            });
        }

        @Override
        public void onEstacionConectada() {
            runOnUiThread(() -> actualizarConexionYBoton());
        }

        @Override
        public void onEstacionDesconectada() {
            runOnUiThread(() -> {
                cancelarComandoPendiente();
                actualizarConexionYBoton();
                avisarFinalizacionManualSiCorresponde();
            });
        }

        @Override
        public void onEstadoRecibido(String estado) {
            runOnUiThread(() -> {
                confirmarComandoSiCorresponde(estado);
                actualizarEstado(estado);
                sincronizarServicio(estado);
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

        iconoEstadoConexion = findViewById(R.id.iconoEstadoConexion);

        bloqueDatosSecado = findViewById(R.id.bloqueDatosSecado);
        bloqueMensajeEstado = findViewById(R.id.bloqueMensajeEstado);

        progressSecado = findViewById(R.id.progressSecado);
        btnCiclo = findViewById(R.id.btnCiclo);

        findViewById(R.id.btnVolver).setOnClickListener(v -> finish());

        btnCiclo.setOnClickListener(v -> cambiarEstadoCiclo());

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
        handler.removeCallbacks(timeoutComando);
    }

    private void cargarUltimosDatos() {
        String ultimoEstado = mqttManager.getUltimoEstado();
        Integer ultimaHumedad = mqttManager.getUltimaHumedad();

        if (ultimoEstado != null) {
            actualizarEstado(ultimoEstado);
            sincronizarServicio(ultimoEstado);
        } else {
            tvEstado.setText(R.string.estado_espera);
            bloqueDatosSecado.setVisibility(View.GONE);
            mostrarMensajeEspera();
        }

        if (ultimaHumedad != null) {
            actualizarHumedad(ultimaHumedad);
        }

        actualizarConexionYBoton();
        actualizarUltimaActualizacion();
    }

    private void cambiarEstadoCiclo() {
        if (comandoPendiente) {
            return;
        }

        String estadoActual = mqttManager.getUltimoEstado();

        if (ESTADO_ESPERA.equals(estadoActual)) {
            enviarComando(MqttManager.COMANDO_INICIAR, ESTADO_MONITOREANDO);
        } else {
            enviarComando(MqttManager.COMANDO_FINALIZAR, ESTADO_ESPERA);
        }
    }

    private void enviarComando(String comando, String nuevoEstadoEsperado) {
        boolean enviado = mqttManager.enviarComando(comando);

        if (!enviado) {
            if (MqttManager.COMANDO_FINALIZAR.equals(comando)) {
                mostrarErrorFinalizacion();
            } else {
                Toast.makeText(this, R.string.error_iniciar_ciclo, Toast.LENGTH_LONG).show();
            }

            actualizarConexionYBoton();
            return;
        }

        // Inicia el servicio cuando se solicita un ciclo.
        if (MqttManager.COMANDO_INICIAR.equals(comando)) {
            iniciarServicio();
        }

        comandoPendiente = true;
        estadoEsperado = nuevoEstadoEsperado;

        actualizarBoton();

        handler.removeCallbacks(timeoutComando);
        handler.postDelayed(timeoutComando, TIMEOUT_COMANDO_MS);
    }

    private void confirmarComandoSiCorresponde(String estado) {
        if (!comandoPendiente || !estado.equals(estadoEsperado)) {
            return;
        }

        cancelarComandoPendiente();
    }

    private void cancelarComandoPendiente() {
        comandoPendiente = false;
        estadoEsperado = null;

        handler.removeCallbacks(timeoutComando);
    }

    private void mostrarErrorFinalizacion() {
        Toast.makeText(this, R.string.error_finalizar_ciclo, Toast.LENGTH_LONG).show();

        Toast.makeText(this, R.string.finalizacion_manual, Toast.LENGTH_LONG).show();
    }

    private void avisarFinalizacionManualSiCorresponde() {
        String estadoActual = mqttManager.getUltimoEstado();

        if (estadoActual != null && !ESTADO_ESPERA.equals(estadoActual)) {

            Toast.makeText(this, R.string.sin_conexion_finalizacion_manual, Toast.LENGTH_LONG).show();
        }
    }

    private void iniciarServicio() {
        Intent intent = new Intent(this, MonitoreoService.class);

        ContextCompat.startForegroundService(this, intent);
    }

    private void sincronizarServicio(String estado) {
        if (ESTADO_ESPERA.equals(estado)) {
            stopService(new Intent(this, MonitoreoService.class));
        } else if (ESTADO_MONITOREANDO.equals(estado) || ESTADO_ROPA_SECA.equals(estado) || ESTADO_LLUVIA.equals(estado)) {
            iniciarServicio();
        }
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
                break;
        }
    }

    private void mostrarMensajeEspera() {
        if (!mqttManager.estaConectado()) {
            mostrarMensaje(R.string.espera_sin_broker_titulo, R.string.espera_sin_broker_mensaje);
        } else if (!mqttManager.estaEstacionConectada()) {
            mostrarMensaje(R.string.espera_sin_estacion_titulo, R.string.espera_sin_estacion_mensaje);
        } else {
            mostrarMensaje(R.string.espera_titulo, R.string.espera_mensaje);
        }
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

        String estadoActual = mqttManager.getUltimoEstado();

        if (estadoActual == null || ESTADO_ESPERA.equals(estadoActual)) {
            mostrarMensajeEspera();
        }
    }

    private void actualizarEstadoConexion() {
        boolean brokerConectado = mqttManager.estaConectado();
        boolean estacionConectada = brokerConectado && mqttManager.estaEstacionConectada();

        tvEstadoConexion.setVisibility(View.VISIBLE);
        iconoEstadoConexion.setVisibility(View.VISIBLE);

        if (estacionConectada) {
            tvEstadoConexion.setText(R.string.monitoreo_conectada);
            tvEstadoConexion.setTextColor(ContextCompat.getColor(this, R.color.success_green));
            iconoEstadoConexion.setImageResource(R.drawable.ic_connection_check);
        } else {
            tvEstadoConexion.setText(R.string.monitoreo_sin_conexion);
            tvEstadoConexion.setTextColor(ContextCompat.getColor(this, R.color.warning_orange));
            iconoEstadoConexion.setImageResource(R.drawable.ic_connection_cross);
        }
    }

    private void actualizarBoton() {
        boolean habilitado = mqttManager.estaConectado() && mqttManager.estaEstacionConectada() && mqttManager.getUltimoEstado() != null && !comandoPendiente;

        btnCiclo.setEnabled(habilitado);
        btnCiclo.setAlpha(habilitado ? 1.0f : 0.5f);
    }

    private void actualizarUltimaActualizacion() {
        long ultimaLectura = mqttManager.getUltimaLecturaSensor();

        if (ultimaLectura == 0) {
            tvUltimaActualizacion.setText(R.string.esperando_actualizacion);
            return;
        }

        long segundos = Math.max(0, (System.currentTimeMillis() - ultimaLectura) / 1000);

        if (segundos < 5) {
            tvUltimaActualizacion.setText(R.string.actualizacion_ahora);

        } else if (segundos < 60) {
            tvUltimaActualizacion.setText(getString(R.string.actualizacion_segundos, segundos));

        } else {
            tvUltimaActualizacion.setText(getString(R.string.actualizacion_minutos, segundos / 60));
        }
    }
}
