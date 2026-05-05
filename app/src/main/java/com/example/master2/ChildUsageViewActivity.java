package com.example.master2;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.GridLayoutManager; // New import
import androidx.recyclerview.widget.RecyclerView;

import com.example.master2.adapters.DaySelectorAdapter;
import com.example.master2.adapters.LegendAdapter; // New import
import com.example.master2.adapters.SUsageAppAdapter;
import com.example.master2.models.SUsageAppInfo;
import com.example.master2.models.SUsageDailyData;
import android.graphics.Color; // Ensure color import
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Activity for PARENT to view child's usage data.
 * Fetches data from Firebase that was uploaded by the child device.
 */
public class ChildUsageViewActivity extends AppCompatActivity {

    private static final String TAG = "ChildUsageView";
    public static final String EXTRA_CHILD_DEVICE_ID = "child_device_id";
    public static final String EXTRA_CHILD_NAME = "child_name";

    // Views
    private TextView tvTitle;
    private TextView tvChildName;
    // private TextView tvLastUpdated; // Removed from UI, shown as Toast
    // private TextView tvUsagePercentage; // Removed per user request
    // private TextView tvTotalScreenTime; // Removed
    // private ProgressBar progressUsage; // Removed per new design
    private TextView tvSelectedDayTime;
    private TextView tvSelectedDayLabel;

    private DonutChart donutChart;
    // private HorizontalBarChart dailyChart; // Removed per user request

    private RecyclerView rvDateStrip;
    private RecyclerView rvLegend; // New View
    private RecyclerView rvAppsUsage;

    private TextView tvEmptyApps;
    private ImageView btnRefresh;
    private ImageView btnBack;
    private View loadingOverlay;

    // Data
    private SUsageAppAdapter appAdapter;
    private List<SUsageDailyData> weeklyUsage = new ArrayList<>();
    private int selectedDayIndex = 6; // Today
    private String childDeviceId;
    private String childName;
    private ValueEventListener usageListener;
    private DatabaseReference usageRef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child_usage_view);

        // Get child device info from intent
        childDeviceId = getIntent().getStringExtra(EXTRA_CHILD_DEVICE_ID);
        childName = getIntent().getStringExtra(EXTRA_CHILD_NAME);

        if (childDeviceId == null || childDeviceId.isEmpty()) {
            Log.e(TAG, "No child device ID provided!");
            Toast.makeText(this, "Error: No child device selected", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Log.d(TAG, "Viewing usage for child: " + childDeviceId + " (" + childName + ")");

        initViews();
        setupRecyclerView();
        setupClickListeners();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadUsageDataFromFirebase();
    }

    @Override
    protected void onPause() {
        super.onPause();
        removeFirebaseListener();
    }

    private void initViews() {
        tvTitle = findViewById(R.id.tvTitle);
        tvChildName = findViewById(R.id.tvChildName);
        // tvLastUpdated = findViewById(R.id.tvLastUpdated);
        // tvDateLabel = findViewById(R.id.tvDateLabel); // Removed
        // tvUsagePercentage = findViewById(R.id.tvUsagePercentage);
        // tvTotalScreenTime = findViewById(R.id.tvTotalScreenTime); // Removed
        // progressUsage = findViewById(R.id.progressUsage);
        // tvSelectedDayTime = findViewById(R.id.tvSelectedDayTime); // Removed
        // tvSelectedDayLabel = findViewById(R.id.tvSelectedDayLabel); // Removed

        donutChart = findViewById(R.id.donutChart);
        // dailyChart = findViewById(R.id.dailyChart); // Removed
        rvDateStrip = findViewById(R.id.rvDateStrip);
        rvLegend = findViewById(R.id.rvLegend); // Bind view
        rvAppsUsage = findViewById(R.id.rvAppsUsage);

        tvEmptyApps = findViewById(R.id.tvEmptyApps);
        // btnPrevDay = findViewById(R.id.btnPrevDay);
        // btnNextDay = findViewById(R.id.btnNextDay);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnBack = findViewById(R.id.btnBack);
        loadingOverlay = findViewById(R.id.loadingOverlay);

        // Set child name
        if (childName != null && !childName.isEmpty()) {
            tvChildName.setText(childName);
        } else {
            tvChildName.setText(childDeviceId);
        }

        // Set today's date
        // SimpleDateFormat sdf = new SimpleDateFormat("'Today,' d MMMM",
        // Locale.getDefault());
        // tvDateLabel.setText(sdf.format(Calendar.getInstance().getTime()));
    }

    private void setupRecyclerView() {
        appAdapter = new SUsageAppAdapter();
        appAdapter.setRemoteMode(true); // Parent viewing child's apps - don't try to load icons
        rvAppsUsage.setLayoutManager(new LinearLayoutManager(this));
        rvAppsUsage.setAdapter(appAdapter);
        rvAppsUsage.setNestedScrollingEnabled(false);

        // Handle App Click -> Scroll to App in InstalledApps Activity
        appAdapter.setOnItemClickListener(appUsage -> {
            android.content.Intent intent = new android.content.Intent(this, ChildInstalledAppsActivity.class);
            intent.putExtra("childDeviceId", childDeviceId); // Fixed key mismatch
            intent.putExtra("childName", childName); // Consistent naming
            intent.putExtra("scrollToPackage", appUsage.getPackageName()); // Pass package to scroll to
            startActivity(intent);
        });
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnRefresh.setOnClickListener(v -> {
            requestChildToUploadData();
            loadUsageDataFromFirebase();
        });

        // btnPrevDay.setOnClickListener(v -> navigateDay(-1));
        // btnNextDay.setOnClickListener(v -> navigateDay(1));

        // Chart bar click listener
        // Chart bar click listener - Updated for HorizontalBarChart
        /*
         * weeklyChart.setOnBarClickListener((index, valueMinutes) -> {
         * selectedDayIndex = index;
         * updateSelectedDayDisplay();
         * });
         */

    }

    private void loadUsageDataFromFirebase() {
        showLoading(true);

        removeFirebaseListener();

        usageRef = FirebaseDatabase.getInstance()
                .getReference("susage_data")
                .child(childDeviceId);

        usageListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                showLoading(false);

                if (!snapshot.exists()) {
                    Log.d(TAG, "No usage data found for child: " + childDeviceId);
                    showEmptyState();
                    // tvLastUpdated removed
                    return;
                }

                parseUsageData(snapshot);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                showLoading(false);
                Log.e(TAG, "Firebase error: " + error.getMessage());
                Toast.makeText(ChildUsageViewActivity.this,
                        "Error loading data: " + error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        };

        usageRef.addValueEventListener(usageListener);
    }

    private void parseUsageData(DataSnapshot snapshot) {
        weeklyUsage.clear();

        // Get last updated timestamp
        Long lastUpdated = snapshot.child("lastUpdated").getValue(Long.class);

        // 🆕 Check heartbeat for device status
        Long lastHeartbeat = snapshot.child("heartbeat").child("lastHeartbeat").getValue(Long.class);

        // Use heartbeat if available, otherwise fall back to lastUpdated
        long lastSyncTime = (lastHeartbeat != null) ? lastHeartbeat : (lastUpdated != null) ? lastUpdated : 0;

        if (lastSyncTime > 0) {
            long timeSinceSync = System.currentTimeMillis() - lastSyncTime;
            long minutesAgo = timeSinceSync / (1000 * 60);

            SimpleDateFormat sdf = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());
            String timeText;

            if (minutesAgo < 1) {
                timeText = "Just now";
            } else if (minutesAgo < 60) {
                timeText = minutesAgo + " min ago";
            } else if (minutesAgo < 1440) { // Less than 24 hours
                long hours = minutesAgo / 60;
                timeText = hours + " hour" + (hours > 1 ? "s" : "") + " ago";
            } else {
                timeText = sdf.format(lastSyncTime);
            }

            // Show warning if data is stale (more than 1 hour old)
            // Show toast if data is stale (more than 1 hour old)
            if (timeSinceSync > 60 * 60 * 1000) { // 1 hour
                Toast.makeText(this, "⚠️ Last synced: " + timeText, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "✅ Last synced: " + timeText, Toast.LENGTH_SHORT).show();
            }
        } else {
            // No sync data yet
        }

        // Parse weekly data
        DataSnapshot weeklyDataSnapshot = snapshot.child("weeklyData");
        if (weeklyDataSnapshot.exists()) {
            // Create a list of date keys for the last 7 days
            Calendar calendar = Calendar.getInstance();
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            List<String> expectedDates = new ArrayList<>();

            for (int i = 6; i >= 0; i--) {
                Calendar dayCalendar = (Calendar) calendar.clone();
                dayCalendar.add(Calendar.DAY_OF_YEAR, -i);
                expectedDates.add(dateFormat.format(dayCalendar.getTime()));
            }

            // Parse each day
            for (String dateKey : expectedDates) {
                DataSnapshot daySnapshot = weeklyDataSnapshot.child(dateKey);

                if (daySnapshot.exists()) {
                    SUsageDailyData dailyData = daySnapshot.getValue(SUsageDailyData.class);
                    if (dailyData != null) {
                        // Firebase auto-deserializes the apps map - NO need to manually parse!
                        // The apps are already in dailyData.getApps()
                        weeklyUsage.add(dailyData);
                    } else {
                        // Create empty day
                        weeklyUsage.add(createEmptyDay(dateKey));
                    }
                } else {
                    // Create empty day
                    weeklyUsage.add(createEmptyDay(dateKey));
                }
            }
        }

        // Ensure we have 7 days
        while (weeklyUsage.size() < 7) {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -(6 - weeklyUsage.size()));
            weeklyUsage.add(0, new SUsageDailyData(cal));
        }

        // Update UI
        selectedDayIndex = 6;
        // updateTodayCard(); // Removed
        setupDateStrip(); // Setup date navigation
        updateSelectedDayDisplay();
    }

    private void setupDateStrip() {
        List<Calendar> days = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

        for (SUsageDailyData data : weeklyUsage) {
            Calendar cal = Calendar.getInstance();
            try {
                if (data.getDateKey() != null) {
                    cal.setTime(sdf.parse(data.getDateKey()));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            days.add(cal);
        }

        DaySelectorAdapter dayAdapter = new DaySelectorAdapter(days, (index, day) -> {
            selectedDayIndex = index;
            updateSelectedDayDisplay();
        });

        rvDateStrip.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvDateStrip.setAdapter(dayAdapter);
        dayAdapter.setSelectedIndex(selectedDayIndex);

        // Scroll to end
        rvDateStrip.scrollToPosition(days.size() - 1);
    }

    private SUsageDailyData createEmptyDay(String dateKey) {
        SUsageDailyData emptyDay = new SUsageDailyData();
        emptyDay.setDateKey(dateKey);
        return emptyDay;
    }

    private void showEmptyState() {
        // tvTotalScreenTime.setText("0h 0m"); // Removed
        // tvSelectedDayTime.setText("0h 0m");
        // tvSelectedDayLabel.setText("No data");

        tvEmptyApps.setVisibility(View.VISIBLE);
        rvAppsUsage.setVisibility(View.GONE);

        if (donutChart != null)
            donutChart.setVisibility(View.GONE);
    }

    private void updateTodayCard() {
        if (weeklyUsage == null || weeklyUsage.isEmpty())
            return;

        SUsageDailyData todayUsage = weeklyUsage.get(weeklyUsage.size() - 1); // Last day is today

        // int percentage = todayUsage.getUsagePercentage();
        // tvUsagePercentage.setText(percentage + "%");
        // progressUsage.setProgress(percentage); // Removed
        // tvTotalScreenTime.setText(todayUsage.getShortFormattedTime()); // Removed
    }

    /*
     * private void updateWeeklyChart() {
     * // Removed
     * }
     */

    /*
     * private void navigateDay(int direction) {
     * // Removed
     * }
     */

    /*
     * private void updateTopAppsChart(List<SUsageAppInfo> apps) {
     * // Removed
     * }
     */

    private void updateSelectedDayDisplay() {
        if (weeklyUsage == null || weeklyUsage.isEmpty() ||
                selectedDayIndex < 0 || selectedDayIndex >= weeklyUsage.size()) {
            return;
        }

        SUsageDailyData selectedDay = weeklyUsage.get(selectedDayIndex);

        // Update time display - VIA DONUT
        // tvSelectedDayTime.setText(selectedDay.getShortFormattedTime());
        // tvSelectedDayLabel.setText(selectedDay.getFormattedDate());

        // Update apps list
        List<SUsageAppInfo> appList = selectedDay.getAppList();

        // Sort by usage time descending
        Collections.sort(appList, (a, b) -> Long.compare(b.getUsageTimeMillis(), a.getUsageTimeMillis()));

        appAdapter.updateData(appList);

        // updateTopAppsChart(appList); // Removed Horizontal Chart
        updateDonutChart(appList, selectedDay.getShortFormattedTime());

        // Show/hide empty state
        if (appList.isEmpty()) {
            tvEmptyApps.setVisibility(View.VISIBLE);
            rvAppsUsage.setVisibility(View.GONE);
        } else {
            tvEmptyApps.setVisibility(View.GONE);
            rvAppsUsage.setVisibility(View.VISIBLE);
        }

        // Update selection in adapter (if needed, but usually click triggers it)
        if (rvDateStrip.getAdapter() instanceof DaySelectorAdapter) {
            ((DaySelectorAdapter) rvDateStrip.getAdapter()).setSelectedIndex(selectedDayIndex);
        }
    }

    private void updateDonutChart(List<SUsageAppInfo> apps, String centerText) {
        if (donutChart == null)
            return;

        if (apps == null || apps.isEmpty()) {
            donutChart.setVisibility(View.GONE);
            return;
        }
        donutChart.setVisibility(View.VISIBLE);

        List<Float> values = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        // Colors palette (Distinct / Vibrant / Premium)
        int[] palette = {
                Color.parseColor("#4F46E5"), // Indigo 600
                Color.parseColor("#EC4899"), // Pink 500
                Color.parseColor("#10B981"), // Emerald 500
                Color.parseColor("#F59E0B"), // Amber 500
                Color.parseColor("#06B6D4"), // Cyan 500
                Color.parseColor("#8B5CF6"), // Violet 500
                Color.parseColor("#F43F5E") // Rose 500
        };

        int count = Math.min(apps.size(), 5);
        for (int i = 0; i < count; i++) {
            values.add((float) apps.get(i).getUsageTimeMillis());
            labels.add(apps.get(i).getAppName());
            colors.add(palette[i % palette.length]);
        }

        donutChart.setData(values, colors, labels);
        donutChart.setCenterText(centerText, "Total");

        // Update Legend
        LegendAdapter legendAdapter = new LegendAdapter(labels, colors);
        rvLegend.setLayoutManager(new GridLayoutManager(this, 2)); // 2 Columns
        rvLegend.setAdapter(legendAdapter);
    }

    private void requestChildToUploadData() {
        // Set a flag in Firebase that the child device will listen to
        DatabaseReference requestRef = FirebaseDatabase.getInstance()
                .getReference("susage_update_requests")
                .child(childDeviceId);

        requestRef.child("requestUpdate").setValue(true)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Update request sent to child device");
                    Toast.makeText(this, "Requesting fresh data from child...", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to send update request: " + e.getMessage());
                });
    }

    private void removeFirebaseListener() {
        if (usageRef != null && usageListener != null) {
            usageRef.removeEventListener(usageListener);
            usageListener = null;
        }
    }

    private void showLoading(boolean show) {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }
}
