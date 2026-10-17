package com.unlam.soa.grupo3.ropaseca.ia;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class ConexionActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conexion);
        Pantalla.ajustarBarras(this);
        findViewById(R.id.btnVolver).setOnClickListener(v -> finish());
    }
}
