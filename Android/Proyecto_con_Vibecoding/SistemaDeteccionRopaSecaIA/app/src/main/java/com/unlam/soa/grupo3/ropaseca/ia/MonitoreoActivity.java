package com.unlam.soa.grupo3.ropaseca.ia;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class MonitoreoActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_monitoreo);
        Pantalla.ajustarBarras(this);
        findViewById(R.id.btnVolver).setOnClickListener(v -> finish());
    }
}
