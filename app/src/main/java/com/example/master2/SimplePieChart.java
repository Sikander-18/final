package com.example.master2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class SimplePieChart extends View {
    private List<PieSlice> slices;
    private Paint paint;
    private RectF bounds;

    public SimplePieChart(Context context) {
        super(context);
        init();
    }

    public SimplePieChart(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SimplePieChart(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        slices = new ArrayList<>();
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bounds = new RectF();
    }

    public void setData(List<PieSlice> newSlices) {
        android.util.Log.d("SimplePieChart", "setData called with " + newSlices.size() + " slices");
        slices.clear();
        slices.addAll(newSlices);
        android.util.Log.d("SimplePieChart", "Slices set, calling invalidate()");
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float padding = 50f;
        float size = Math.min(w, h) - (padding * 2);
        float left = (w - size) / 2;
        float top = (h - size) / 2;
        bounds.set(left, top, left + size, top + size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        android.util.Log.d("SimplePieChart", "onDraw called, slices size: " + slices.size());
        if (slices.isEmpty()) {
            android.util.Log.d("SimplePieChart", "No slices to draw");
            return;
        }

        float startAngle = 0f;
        float totalValue = 0f;

        // Calculate total value
        for (PieSlice slice : slices) {
            totalValue += slice.value;
        }

        // Draw background circle
        Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setColor(android.graphics.Color.parseColor("#F8FAFC"));
        canvas.drawCircle(bounds.centerX(), bounds.centerY(), bounds.width() / 2, backgroundPaint);

        // Draw slices with improved styling
        for (PieSlice slice : slices) {
            float sweepAngle = (slice.value / totalValue) * 360f;
            
            // Main slice
            paint.setColor(slice.color);
            canvas.drawArc(bounds, startAngle, sweepAngle, true, paint);
            
            // Add a subtle shadow effect
            Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadowPaint.setColor(android.graphics.Color.parseColor("#20000000"));
            shadowPaint.setStyle(Paint.Style.STROKE);
            shadowPaint.setStrokeWidth(2f);
            canvas.drawArc(bounds, startAngle, sweepAngle, true, shadowPaint);
            
            startAngle += sweepAngle;
        }

        // Draw center circle for modern look
        Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerPaint.setColor(android.graphics.Color.WHITE);
        float centerRadius = bounds.width() / 6;
        canvas.drawCircle(bounds.centerX(), bounds.centerY(), centerRadius, centerPaint);
    }

    public static class PieSlice {
        private final String label;
        private final float value;
        private final int color;

        public PieSlice(String label, float value, int color) {
            this.label = label;
            this.value = value;
            this.color = color;
        }

        public String getLabel() { return label; }
        public float getValue() { return value; }
        public int getColor() { return color; }
    }
}