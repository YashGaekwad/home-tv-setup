package com.yashg.electricity;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Daily kWh as rounded bars, the latest day highlighted, the value over each bar. */
final class BarChartView extends View {
    private double[] values = new double[0];
    private String[] labels = new String[0];
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    BarChartView(Context c) {
        super(c);
        text.setColor(0xB3FFFFFF);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
        grid.setColor(0x22FFFFFF);
        grid.setStrokeWidth(2);
    }

    void setData(double[] values, String[] labels) {
        this.values = values;
        this.labels = labels;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = values.length;
        float w = getWidth(), h = getHeight();
        if (n == 0) {
            text.setTextSize(h * 0.08f);
            canvas.drawText("No daily data yet", w / 2, h / 2, text);
            return;
        }
        double max = 1;
        for (double v : values) max = Math.max(max, v);
        float labelH = h * 0.12f, valueH = h * 0.10f;
        float top = valueH, bottom = h - labelH;
        canvas.drawLine(0, bottom, w, bottom, grid);
        float slot = w / n, bw = slot * 0.62f;
        text.setTextSize(Math.min(slot * 0.45f, h * 0.07f));
        for (int i = 0; i < n; i++) {
            float cx = slot * i + slot / 2;
            float bh = (float) ((bottom - top) * values[i] / max);
            boolean last = i == n - 1;
            bar.setColor(last ? 0xFFF5B700 : 0xFF4C6FFF);
            r.set(cx - bw / 2, bottom - Math.max(bh, 3), cx + bw / 2, bottom);
            canvas.drawRoundRect(r, bw * 0.25f, bw * 0.25f, bar);
            text.setColor(last ? Color.WHITE : 0xB3FFFFFF);
            canvas.drawText(fmt(values[i]), cx, bottom - bh - valueH * 0.25f, text);
            if (labels.length == n && (n <= 16 || i % 2 == (n - 1) % 2)) {
                text.setColor(0x80FFFFFF);
                canvas.drawText(labels[i], cx, h - labelH * 0.2f, text);
            }
        }
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(java.util.Locale.US, "%.1f", v);
    }
}
