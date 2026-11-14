package com.unlam.soa.grupo3.ropaseca;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;

public class CircularProgressView extends View {

    private final Paint fondoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progresoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int progreso = 0;

    public CircularProgressView(Context context) {
        super(context);
        inicializar();
    }

    public CircularProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        inicializar();
    }

    public CircularProgressView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        inicializar();
    }

    private void inicializar() {
        float grosor = getResources().getDisplayMetrics().density * 12f;

        fondoPaint.setStyle(Paint.Style.STROKE);
        fondoPaint.setStrokeWidth(grosor);
        fondoPaint.setColor(Color.parseColor("#EEE6FB"));

        progresoPaint.setStyle(Paint.Style.STROKE);
        progresoPaint.setStrokeWidth(grosor);
        progresoPaint.setStrokeCap(Paint.Cap.ROUND);
        progresoPaint.setColor(Color.parseColor("#8967D1"));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        float grosor = progresoPaint.getStrokeWidth();
        float margen = grosor / 2f + 2f;

        float size = Math.min(getWidth(), getHeight());
        float left = (getWidth() - size) / 2f;
        float top = (getHeight() - size) / 2f;

        rect.set(left + margen, top + margen, left + size - margen, top + size - margen);

        canvas.drawArc(rect, 0, 360, false, fondoPaint);

        if (progreso > 0) {
            canvas.drawArc(rect, -90, 360f * progreso / 100f, false, progresoPaint);
        }
    }

    public void setProgress(int progreso) {
        this.progreso = Math.max(0, Math.min(100, progreso));
        invalidate();
    }

    public int getProgress() {
        return progreso;
    }
}
