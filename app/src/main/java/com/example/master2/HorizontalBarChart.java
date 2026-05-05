package com.example.master2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * A modern horizontal bar chart for displaying Top Apps.
 * Features: Gradient bars, rounded corners, left-side labels, click interaction.
 */
public class HorizontalBarChart extends View {

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<Float> values = new ArrayList<>();
    private List<String> labels = new ArrayList<>();
    private List<String> valueLabels = new ArrayList<>(); // e.g. "45m"
    private int selectedIndex = -1;

    private OnItemClickListener listener;

    private int primaryColor = Color.parseColor("#4A6CF7");
    private int secondaryColor = Color.parseColor("#4A6CF7"); // For gradient

    // Layout config
    private final float barHeight = 40f; // dp
    private final float barSpacing = 24f; // dp
    private float density;
    private final List<RectF> touchAreas = new ArrayList<>();

    public interface OnItemClickListener {
        void onItemClick(int index);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public HorizontalBarChart(Context context) {
        super(context);
        init();
    }

    public HorizontalBarChart(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        
        labelPaint.setColor(Color.BLACK);
        labelPaint.setTextSize(14 * density);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        
        valuePaint.setColor(Color.GRAY);
        valuePaint.setTextSize(12 * density);
        valuePaint.setTextAlign(Paint.Align.RIGHT);
        
        // bgPaint.setColor(Color.parseColor("#F3F4F6")); // Light gray track
        bgPaint.setColor(0); // Transparent track
    }

    public void setData(List<Float> values, List<String> labels, List<String> valueLabels) {
        this.values = new ArrayList<>(values);
        this.labels = new ArrayList<>(labels);
        this.valueLabels = new ArrayList<>(valueLabels);
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Height depends on number of items
        int count = values.size();
        float totalHeight = count * (barHeight * density + barSpacing * density) + (barSpacing * density); // Add padding
        
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, (int) totalHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (values.isEmpty()) return;

        float maxVal = 0;
        for (float v : values) maxVal = Math.max(maxVal, v);
        if (maxVal == 0) maxVal = 1;

        int width = getWidth();
        float bHeight = barHeight * density;
        float bSpace = barSpacing * density;
        
        // Layout Config
        float labelWidth = 100 * density; // Reserved for "Instagram"
        float valueWidth = 50 * density;  // Reserved for "45m"
        float maxBarWidth = width - labelWidth - valueWidth - (16 * density);
        
        float currentY = bSpace;
        touchAreas.clear();

        for (int i = 0; i < values.size(); i++) {
            float val = values.get(i);
            String label = labels.size() > i ? labels.get(i) : "";
            String valTxt = valueLabels.size() > i ? valueLabels.get(i) : "";
            
            // 1. Draw Label
            float labelY = currentY + bHeight / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f;
            canvas.drawText(label, 0, labelY, labelPaint);
            
            // 2. Draw Bar
            float barW = (val / maxVal) * maxBarWidth;
            if (barW < 4 * density) barW = 4 * density; // Min width
            
            float barLeft = labelWidth;
            RectF rect = new RectF(barLeft, currentY, barLeft + barW, currentY + bHeight);
            
            // Gradient
            barPaint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    primaryColor, Color.parseColor("#7C96FF"), Shader.TileMode.CLAMP));
            
            canvas.drawRoundRect(rect, 8 * density, 8 * density, barPaint);
            
            // 3. Draw Value
            // canvas.drawText(valTxt, width, labelY, valuePaint); 
            // Better: Draw inside bar if wide enough, or outside if short?
            // User requested Standard Design: Label - Bar - Value
            canvas.drawText(valTxt, width, labelY, valuePaint);
            
            // Save touch area
            touchAreas.add(new RectF(0, currentY, width, currentY + bHeight));
            
            currentY += bHeight + bSpace;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
         if (event.getAction() == MotionEvent.ACTION_DOWN) {
            float x = event.getX();
            float y = event.getY();
            for (int i = 0; i < touchAreas.size(); i++) {
                if (touchAreas.get(i).contains(x, y)) {
                    if (listener != null) listener.onItemClick(i);
                    return true;
                }
            }
        }
        return super.onTouchEvent(event);
    }
}
