
package com.unlam.soa.grupo3.ropaseca;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

public class MonitoreoService extends Service {

    private static final String CANAL_MONITOREO = "monitoreo_ropa";
    private static final String CANAL_ALERTAS = "alertas_ropa";

    private static final int ID_MONITOREO = 100;
    private static final int ID_ALERTA_ROPA = 101;
    private static final int ID_ALERTA_LLUVIA = 102;

    private static final String ESTADO_ESPERA = "ESPERA";
    private static final String ESTADO_ROPA_SECA = "ROPA_SECA";
    private static final String ESTADO_LLUVIA = "LLUVIA";

    private MqttManager mqttManager;
    private String ultimoEstadoProcesado;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final MqttManager.MqttListener mqttListener =
            new MqttManager.MqttListener() {

                @Override
                public void onBrokerConectado() {
                }

                @Override
                public void onBrokerDesconectado() {
                }

                @Override
                public void onEstacionConectada() {
                }

                @Override
                public void onEstacionDesconectada() {
                }

                @Override
                public void onHumedadRecibida(int humedad) {
                }

                @Override
                public void onEstadoRecibido(String estado) {
                    handler.post(() -> procesarEstado(estado));
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();

        crearCanalesNotificacion();

        mqttManager = MqttManager.getInstance();

        // Evita notificar un estado antiguo al iniciar.
        ultimoEstadoProcesado = mqttManager.getUltimoEstado();

        mqttManager.agregarListener(mqttListener);
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {
        Notification notificacion = crearNotificacionMonitoreo();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    ID_MONITOREO,
                    notificacion,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            );
        } else {
            startForeground(ID_MONITOREO, notificacion);
        }

        // Reutiliza la conexión del MqttManager.
        mqttManager.conectar();

        return START_STICKY;
    }

    private void procesarEstado(String estado) {
        if (estado == null) {
            return;
        }

        // Si el ESP32 vuelve a ESPERA, termina el monitoreo.
        if (ESTADO_ESPERA.equals(estado)) {
            ultimoEstadoProcesado = estado;
            stopSelf();
            return;
        }

        // No repetir alertas por mensajes del mismo estado.
        if (estado.equals(ultimoEstadoProcesado)) {
            return;
        }

        ultimoEstadoProcesado = estado;

        switch (estado) {
            case ESTADO_ROPA_SECA:
                mostrarAlerta(
                        ID_ALERTA_ROPA,
                        "¡La ropa está seca!",
                        "La ropa ya está seca. Podés retirarla del tendedero."
                );
                break;

            case ESTADO_LLUVIA:
                mostrarAlerta(
                        ID_ALERTA_LLUVIA,
                        "¡Está lloviendo!",
                        "Está lloviendo mientras tenés ropa secándose."
                );
                break;

            default:
                // MONITOREANDO_SECADO u otros estados:
                // no necesitan una alerta.
                break;
        }
    }

    private void crearCanalesNotificacion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager manager =
                getSystemService(NotificationManager.class);

        if (manager == null) {
            return;
        }

        NotificationChannel canalMonitoreo =
                new NotificationChannel(
                        CANAL_MONITOREO,
                        "Monitoreo de ropa",
                        NotificationManager.IMPORTANCE_LOW
                );

        canalMonitoreo.setDescription(
                "Notificación mientras se monitorea el secado"
        );

        NotificationChannel canalAlertas =
                new NotificationChannel(
                        CANAL_ALERTAS,
                        "Alertas de ropa",
                        NotificationManager.IMPORTANCE_HIGH
                );

        canalAlertas.setDescription(
                "Avisos cuando la ropa está seca o empieza a llover"
        );

        manager.createNotificationChannel(canalMonitoreo);
        manager.createNotificationChannel(canalAlertas);
    }

    private Notification crearNotificacionMonitoreo() {
        return new NotificationCompat.Builder(
                this,
                CANAL_MONITOREO
        )
                .setSmallIcon(R.drawable.ic_notificacion_ropa)
                .setContentTitle("Monitoreando la ropa")
                .setContentText(
                        "El secado se está monitoreando, te avisaremos cuando la ropa esté seca o si empieza a llover."
                )
                .setContentIntent(crearIntentAbrirMonitoreo())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void mostrarAlerta(
            int id,
            String titulo,
            String mensaje
    ) {
        NotificationManager manager =
                getSystemService(NotificationManager.class);

        if (manager == null) {
            return;
        }

        Notification notificacion =
                new NotificationCompat.Builder(
                        this,
                        CANAL_ALERTAS
                )
                        .setSmallIcon(R.drawable.ic_notificacion_ropa)
                        .setContentTitle(titulo)
                        .setContentText(mensaje)
                        .setContentIntent(crearIntentAbrirMonitoreo())
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build();

        manager.notify(id, notificacion);
    }

    private PendingIntent crearIntentAbrirMonitoreo() {
        Intent intent = new Intent(
                this,
                MonitoreoActivity.class
        );

        intent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        return PendingIntent.getActivity(
                this,
                0,
                intent,
                flags
        );
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);

        if (mqttManager != null) {
            mqttManager.quitarListener(mqttListener);
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
