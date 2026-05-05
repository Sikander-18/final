package com.example.susage;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.susage.adapters.AppUsageAdapter;
import com.example.susage.models.AppUsageInfo;
import com.example.susage.models.DailyUsage;
import com.example.susage.utils.PermissionHelper;
import com.example.susage.utils.UsageDataManager;
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextView userName;
    private TextView todayDate;
    private TextView usagePercentage;
    private TextView totalScreenTime;
    private ProgressBar usageProgressBar;
    private TextView weeklyTotalTime;
    private TextView activityDateRange;
    private BarChart weeklyChart;
    private RecyclerView appsRecyclerView;
    private CardView permissionCard;
    private MaterialButton grantPermissionButton;
    private View appLimitsMenu;
    private View contentRestrictionsMenu;
    private View accountSettingsMenu;
    private ImageView prevWeekButton;
    private ImageView nextWeekButton;

    private AppUsageAdapter appUsageAdapter;
    private UsageDataManager usageDataManager;
    
    // Track selected day index (0-6, where 6 is today)
    private int selectedDayIndex = 6;
    private List<DailyUsage> weeklyUsage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        setupRecyclerView();
        setupMenuItems();
        setupNavigationButtons();
        
        usageDataManager = UsageDataManager.getInstance(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        selectedDayIndex = 6; // Reset to today
        checkPermissionAndLoadData();
    }

    private void initViews() {
        userName = findViewById(R.id.userName);
        todayDate = findViewById(R.id.todayDate);
        usagePercentage = findViewById(R.id.usagePercentage);
        totalScreenTime = findViewById(R.id.totalScreenTime);
        usageProgressBar = findViewById(R.id.usageProgressBar);
        weeklyTotalTime = findViewById(R.id.weeklyTotalTime);
        activityDateRange = findViewById(R.id.activityDateRange);
        weeklyChart = findViewById(R.id.weeklyChart);
        appsRecyclerView = findViewById(R.id.appsRecyclerView);
        permissionCard = findViewById(R.id.permissionCard);
        grantPermissionButton = findViewById(R.id.grantPermissionButton);
        appLimitsMenu = findViewById(R.id.appLimitsMenu);
        contentRestrictionsMenu = findViewById(R.id.contentRestrictionsMenu);
        accountSettingsMenu = findViewById(R.id.accountSettingsMenu);
        prevWeekButton = findViewById(R.id.prevWeekButton);
        nextWeekButton = findViewById(R.id.nextWeekButton);

        // Set user name (could be fetched from device owner)
        userName.setText("User");

        // Set today's date
        SimpleDateFormat sdf = new SimpleDateFormat("'Today,' d MMMM", Locale.getDefault());
        todayDate.setText(sdf.format(Calendar.getInstance().getTime()));

        // Setup permission button
        grantPermissionButton.setOnClickListener(v -> {
            PermissionHelper.requestUsageStatsPermission(this);
        });
    }

    private void setupNavigationButtons() {
        prevWeekButton.setOnClickListener(v -> navigateDay(-1));
        nextWeekButton.setOnClickListener(v -> navigateDay(1));
    }

    private void navigateDay(int direction) {
        int newIndex = selectedDayIndex + direction;
        if (newIndex >= 0 && newIndex < 7 && weeklyUsage != null && !weeklyUsage.isEmpty()) {
            selectedDayIndex = newIndex;
            updateSelectedDayDisplay();
        }
    }

    private void updateSelectedDayDisplay() {
        if (weeklyUsage == null || weeklyUsage.isEmpty()) {
            return;
        }

        DailyUsage selected = weeklyUsage.get(selectedDayIndex);
        
        // Update time display for selected day
        long totalMinutes = selected.getTotalScreenTimeMillis() / (1000 * 60);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        
        if (hours > 0) {
            weeklyTotalTime.setText(String.format(Locale.getDefault(), "%dh %02dm", hours, minutes));
        } else {
            weeklyTotalTime.setText(minutes + "m");
        }

        // Update date display
        activityDateRange.setText(selected.getFormattedDate());

        // Update apps list for selected day
        appUsageAdapter.updateData(selected.getAppUsageList());

        // Update chart colors to highlight selected day
        updateChartHighlight();

        // Update navigation button visibility
        prevWeekButton.setAlpha(selectedDayIndex > 0 ? 1.0f : 0.3f);
        nextWeekButton.setAlpha(selectedDayIndex < 6 ? 1.0f : 0.3f);
    }

    private void updateChartHighlight() {
        if (weeklyUsage == null || weeklyUsage.isEmpty()) {
            return;
        }

        ArrayList<Integer> colors = new ArrayList<>();
        for (int i = 0; i < weeklyUsage.size(); i++) {
            if (i == selectedDayIndex) {
                colors.add(getResources().getColor(R.color.chart_bar_highlight, getTheme()));
            } else {
                colors.add(getResources().getColor(R.color.chart_bar_medium, getTheme()));
            }
        }

        BarData data = weeklyChart.getData();
        if (data != null && data.getDataSetCount() > 0) {
            BarDataSet dataSet = (BarDataSet) data.getDataSetByIndex(0);
            dataSet.setColors(colors);
            weeklyChart.invalidate();
        }
    }

    private void setupRecyclerView() {
        appUsageAdapter = new AppUsageAdapter();
        appsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        appsRecyclerView.setAdapter(appUsageAdapter);
        appsRecyclerView.setNestedScrollingEnabled(false);

        appUsageAdapter.setOnItemClickListener(new AppUsageAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(AppUsageInfo appUsageInfo) {
                openAppDetail(appUsageInfo);
            }

            @Override
            public void onInfoClick(AppUsageInfo appUsageInfo) {
                openAppDetail(appUsageInfo);
            }
        });
    }

    private void setupMenuItems() {
        // Setup App Limits menu
        TextView appLimitsTitle = appLimitsMenu.findViewById(R.id.menuTitle);
        TextView appLimitsSubtitle = appLimitsMenu.findViewById(R.id.menuSubtitle);
        appLimitsTitle.setText("App limits");
        appLimitsSubtitle.setText("Set app time limits and block apps");

        // Setup Content Restrictions menu
        TextView contentTitle = contentRestrictionsMenu.findViewById(R.id.menuTitle);
        TextView contentSubtitle = contentRestrictionsMenu.findViewById(R.id.menuSubtitle);
        contentTitle.setText("Content restrictions");
        contentSubtitle.setText("Manage search results, block sites");

        // Setup Account Settings menu
        TextView accountTitle = accountSettingsMenu.findViewById(R.id.menuTitle);
        TextView accountSubtitle = accountSettingsMenu.findViewById(R.id.menuSubtitle);
        accountTitle.setText("Account settings");
        accountSubtitle.setText("Sign-in, privacy and more");
    }

    private void checkPermissionAndLoadData() {
        if (PermissionHelper.hasUsageStatsPermission(this)) {
            permissionCard.setVisibility(View.GONE);
            loadUsageData();
        } else {
            permissionCard.setVisibility(View.VISIBLE);
            showEmptyState();
        }
    }

    private void loadUsageData() {
        // Load weekly usage
        weeklyUsage = usageDataManager.getWeeklyUsage();
        
        // Update today card (always shows today)
        if (weeklyUsage != null && !weeklyUsage.isEmpty()) {
            DailyUsage todayUsage = weeklyUsage.get(6); // Today is index 6
            updateTodayCard(todayUsage);
        }

        // Update chart
        updateWeeklyChart(weeklyUsage);

        // Update selected day display (apps list, time, etc.)
        updateSelectedDayDisplay();
    }

    private void updateTodayCard(DailyUsage todayUsage) {
        int percentage = todayUsage.getUsagePercentage();
        usagePercentage.setText(percentage + "%");
        usageProgressBar.setProgress(percentage);

        // Format total screen time
        long totalMinutes = todayUsage.getTotalScreenTimeMillis() / (1000 * 60);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        if (hours > 0) {
            totalScreenTime.setText(hours + "h");
        } else {
            totalScreenTime.setText(minutes + "m");
        }
    }

    private void updateWeeklyChart(List<DailyUsage> weeklyUsage) {
        if (weeklyUsage == null || weeklyUsage.isEmpty()) {
            return;
        }

        // Create bar entries
        ArrayList<BarEntry> entries = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Integer> colors = new ArrayList<>();

        for (int i = 0; i < weeklyUsage.size(); i++) {
            DailyUsage daily = weeklyUsage.get(i);
            float hoursValue = daily.getScreenTimeHours();
            entries.add(new BarEntry(i, hoursValue));
            labels.add(daily.getShortDayName());

            // Use highlight color for selected day
            if (i == selectedDayIndex) {
                colors.add(getResources().getColor(R.color.chart_bar_highlight, getTheme()));
            } else {
                colors.add(getResources().getColor(R.color.chart_bar_medium, getTheme()));
            }
        }

        BarDataSet dataSet = new BarDataSet(entries, "Screen Time");
        dataSet.setColors(colors);
        dataSet.setDrawValues(false);

        BarData barData = new BarData(dataSet);
        barData.setBarWidth(0.5f);

        // Configure chart
        weeklyChart.setData(barData);
        weeklyChart.getDescription().setEnabled(false);
        weeklyChart.getLegend().setEnabled(false);
        weeklyChart.setDrawGridBackground(false);
        weeklyChart.setDrawBorders(false);
        weeklyChart.setTouchEnabled(false);
        weeklyChart.setDragEnabled(false);
        weeklyChart.setScaleEnabled(false);
        weeklyChart.setPinchZoom(false);
        weeklyChart.setExtraBottomOffset(10f);

        // Configure X axis
        XAxis xAxis = weeklyChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(false);
        xAxis.setDrawAxisLine(false);
        xAxis.setGranularity(1f);
        xAxis.setValueFormatter(new IndexAxisValueFormatter(labels));
        xAxis.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
        xAxis.setTextSize(11f);

        // Configure Y axis
        YAxis leftAxis = weeklyChart.getAxisLeft();
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(Color.parseColor("#E0E0E0"));
        leftAxis.setDrawAxisLine(false);
        leftAxis.setAxisMinimum(0f);
        leftAxis.setTextColor(getResources().getColor(R.color.text_tertiary, getTheme()));
        leftAxis.setTextSize(10f);
        leftAxis.setLabelCount(4, true);

        weeklyChart.getAxisRight().setEnabled(false);

        weeklyChart.invalidate();
    }

    private void showEmptyState() {
        usagePercentage.setText("0%");
        totalScreenTime.setText("0h");
        usageProgressBar.setProgress(0);
        weeklyTotalTime.setText("0h 0m");
        appUsageAdapter.updateData(new ArrayList<>());
        
        // Show empty chart
        weeklyChart.clear();
        weeklyChart.invalidate();
    }

    private void openAppDetail(AppUsageInfo appUsageInfo) {
        Intent intent = new Intent(this, AppDetailActivity.class);
        intent.putExtra("package_name", appUsageInfo.getPackageName());
        intent.putExtra("app_name", appUsageInfo.getAppName());
        startActivity(intent);
    }
}