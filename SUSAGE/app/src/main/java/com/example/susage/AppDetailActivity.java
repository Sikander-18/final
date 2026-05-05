package com.example.susage;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.susage.models.DailyUsage;
import com.example.susage.utils.UsageDataManager;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;

import java.util.ArrayList;
import java.util.List;

public class AppDetailActivity extends AppCompatActivity {

    private ImageView backButton;
    private TextView appNameTitle;
    private ImageView appIcon;
    private TextView appName;
    private TextView appUsageTime;
    private TextView appUsageDate;
    private BarChart appUsageChart;
    private ImageView prevDayButton;
    private ImageView nextDayButton;

    private String packageName;
    private String appNameStr;
    private UsageDataManager usageDataManager;
    private List<DailyUsage> weeklyUsage;
    private int currentDayIndex = 6; // Start with today (last index)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_app_detail);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Get data from intent
        packageName = getIntent().getStringExtra("package_name");
        appNameStr = getIntent().getStringExtra("app_name");

        initViews();
        loadAppData();
    }

    private void initViews() {
        backButton = findViewById(R.id.backButton);
        appNameTitle = findViewById(R.id.appNameTitle);
        appIcon = findViewById(R.id.appIcon);
        appName = findViewById(R.id.appName);
        appUsageTime = findViewById(R.id.appUsageTime);
        appUsageDate = findViewById(R.id.appUsageDate);
        appUsageChart = findViewById(R.id.appUsageChart);
        prevDayButton = findViewById(R.id.prevDayButton);
        nextDayButton = findViewById(R.id.nextDayButton);

        // Set app name in header
        appNameTitle.setText(appNameStr != null ? appNameStr : "App");
        appName.setText(appNameStr != null ? appNameStr : "App");

        // Back button
        backButton.setOnClickListener(v -> finish());

        // Navigation buttons
        prevDayButton.setOnClickListener(v -> navigateDay(-1));
        nextDayButton.setOnClickListener(v -> navigateDay(1));

        // Load app icon
        loadAppIcon();
    }

    private void loadAppIcon() {
        if (packageName != null) {
            try {
                ApplicationInfo appInfo = getPackageManager().getApplicationInfo(packageName, 0);
                Drawable icon = getPackageManager().getApplicationIcon(appInfo);
                appIcon.setImageDrawable(icon);
            } catch (PackageManager.NameNotFoundException e) {
                appIcon.setImageResource(R.mipmap.ic_launcher);
            }
        } else {
            appIcon.setImageResource(R.mipmap.ic_launcher);
        }
    }

    private void loadAppData() {
        usageDataManager = UsageDataManager.getInstance(this);
        
        if (packageName != null) {
            weeklyUsage = usageDataManager.getAppWeeklyUsage(packageName);
        } else {
            weeklyUsage = new ArrayList<>();
        }

        updateDayDisplay();
        updateChart();
    }

    private void navigateDay(int direction) {
        int newIndex = currentDayIndex + direction;
        if (newIndex >= 0 && newIndex < weeklyUsage.size()) {
            currentDayIndex = newIndex;
            updateDayDisplay();
        }
    }

    private void updateDayDisplay() {
        if (weeklyUsage == null || weeklyUsage.isEmpty()) {
            appUsageTime.setText("0m");
            appUsageDate.setText("No data");
            return;
        }

        DailyUsage current = weeklyUsage.get(currentDayIndex);
        
        // Update time display
        long totalMinutes = current.getTotalScreenTimeMillis() / (1000 * 60);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        if (hours > 0) {
            appUsageTime.setText(hours + "h " + minutes + "m");
        } else {
            appUsageTime.setText(minutes + "m");
        }

        // Update date display
        appUsageDate.setText(current.getFormattedDate());

        // Update navigation button visibility
        prevDayButton.setAlpha(currentDayIndex > 0 ? 1.0f : 0.3f);
        nextDayButton.setAlpha(currentDayIndex < weeklyUsage.size() - 1 ? 1.0f : 0.3f);
    }

    private void updateChart() {
        if (weeklyUsage == null || weeklyUsage.isEmpty()) {
            appUsageChart.clear();
            appUsageChart.invalidate();
            return;
        }

        ArrayList<BarEntry> entries = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Integer> colors = new ArrayList<>();

        for (int i = 0; i < weeklyUsage.size(); i++) {
            DailyUsage daily = weeklyUsage.get(i);
            float hoursValue = daily.getScreenTimeHours();
            entries.add(new BarEntry(i, hoursValue));
            labels.add(daily.getShortDayName());

            // Use highlight color for the selected day
            if (i == currentDayIndex) {
                colors.add(getResources().getColor(R.color.chart_bar_highlight, getTheme()));
            } else {
                colors.add(getResources().getColor(R.color.chart_bar_medium, getTheme()));
            }
        }

        BarDataSet dataSet = new BarDataSet(entries, appNameStr);
        dataSet.setColors(colors);
        dataSet.setDrawValues(false);

        BarData barData = new BarData(dataSet);
        barData.setBarWidth(0.5f);

        // Configure chart
        appUsageChart.setData(barData);
        appUsageChart.getDescription().setEnabled(false);
        appUsageChart.getLegend().setEnabled(false);
        appUsageChart.setDrawGridBackground(false);
        appUsageChart.setDrawBorders(false);
        appUsageChart.setTouchEnabled(true);
        appUsageChart.setDragEnabled(false);
        appUsageChart.setScaleEnabled(false);
        appUsageChart.setPinchZoom(false);
        appUsageChart.setExtraBottomOffset(10f);

        // Configure X axis
        XAxis xAxis = appUsageChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setDrawAxisLine(false);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new IndexAxisValueFormatter(labels));
        xAxis.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
        xAxis.setTextSize(11f);

        // Configure Y axis
        YAxis leftAxis = appUsageChart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(Color.parseColor("#E0E0E0"));
        leftAxis.setDrawAxisLine(false);
        leftAxis.setAxisMinimum(0f);
        leftAxis.setTextColor(getResources().getColor(R.color.text_tertiary, getTheme()));
        leftAxis.setTextSize(10f);
        leftAxis.setLabelCount(4, true);

        appUsageChart.getAxisRight().setEnabled(false);

        appUsageChart.invalidate();
    }
}
