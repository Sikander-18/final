package com.example.master2;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.List;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Date;

public class ChildUsageActivity extends AppCompatActivity {
    private static final String TAG = "ChildUsageActivity";
    private TextView tvTotalTime, tvChildName, tvAppsCount, tvDateLabel;
    private TextView tvChartTitle;
    private SimpleBarChart barChart;
    private RecyclerView rvApp;
    private AppUsageAdapter adapter;
    private List<List<com.example.master2.models.AppUsage>> dailyApps = new java.util.ArrayList<>();
    private List<String> dailyTotalTexts = new java.util.ArrayList<>();
    private DatabaseReference snapRef;
    private String deviceId;
    
    // Period selection
    private Button btnDay;
    private String currentPeriod = "day";
    
    // Enhanced UI state
    private boolean isDataLoading = false;
    private int selectedDayIndex = -1; // Track which day is currently selected
    
    // Date formatting
    private SimpleDateFormat dayFormat = new SimpleDateFormat("EEE", Locale.getDefault()); // Mon, Tue, Wed
    private SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d", Locale.getDefault()); // Jan 15
    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child_usage);

        // Initialize all UI components
        tvChildName = findViewById(R.id.tvChildName);
        tvTotalTime = findViewById(R.id.tvTotalTime);
        tvAppsCount = findViewById(R.id.tvAppsCount);
        tvDateLabel = findViewById(R.id.tvDateLabel);
        tvChartTitle = findViewById(R.id.tvChartTitle);
        
        barChart = findViewById(R.id.barChart);
        rvApp = findViewById(R.id.rvAppUsage);
        rvApp.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppUsageAdapter();
        rvApp.setAdapter(adapter);
        
        // Initialize period buttons
        btnDay = findViewById(R.id.btnDay);
        
        // Setup period button listeners
        setupPeriodButtons();

        deviceId = getIntent().getStringExtra("deviceId");
        Log.d(TAG, "ChildUsageActivity deviceId: " + deviceId);
        String deviceName = getIntent().getStringExtra("deviceName");
        tvChildName.setText(deviceName != null ? deviceName : "Child Device");
        
        Log.d(TAG, "Fetching usage data for device: " + deviceId + " (" + deviceName + ")");

        // Make sure we have a deviceId
        if (deviceId == null || deviceId.isEmpty()) {
            Toast.makeText(this, "No device ID provided", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "No device ID provided in intent");
            return;
        }

        // Initialize Firebase reference for 7-day rolling data structure
        snapRef = FirebaseDatabase.getInstance()
                .getReference("usage_7day")
                .child(deviceId);
        Log.d(TAG, "Listening at: usage_7day/" + deviceId);
        
        // Fetch 7-day rolling window data
        fetch7DayRollingData();
        
        // Add value listener for real-time updates of today's data
        SimpleDateFormat dateKeyFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        String todayKey = dateKeyFormat.format(new Date());
        
        snapRef.child(todayKey).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                Log.d(TAG, "Today's data updated - exists: " + snapshot.exists());
                if (snapshot.exists()) {
                    // Process today's updated data
                    process7DayData(snapshot, todayKey, 6); // Today is index 6 in 7-day view
                }
            }
            
            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Firebase error for today's data: " + error.getMessage());
                Toast.makeText(ChildUsageActivity.this, "Error loading today's data: " + error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        // Manual refresh button
        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            Log.d(TAG, "Manual refresh requested");
            fetchLatestSnapshot();
        });
        
        // Back button functionality
        findViewById(R.id.btn_back).setOnClickListener(v -> {
            finish();
        });
    }

    private void fetchLatestSnapshot(){
        Log.d(TAG, "Manual refresh requested - attempting fresh data fetch");
        Toast.makeText(this, "Refreshing data...", Toast.LENGTH_SHORT).show();
        
        // Clear existing data
        dailyApps.clear();
        dailyTotalTexts.clear();
        
        // Try the data fetch mechanism again
        fetch7DayRollingData();
    }
    
    /**
     * 🎯 Fetch 7-day rolling window data from the new structure
     */
    private void fetch7DayRollingData() {
        Log.d(TAG, "Fetching 7-day rolling window data...");
        
        dailyApps.clear();
        dailyTotalTexts.clear();
        List<Float> chartValues = new ArrayList<>();
        List<String> dayLabels = new ArrayList<>();
        
        // Get the last 7 days
        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat dateKeyFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        SimpleDateFormat dayLabelFormat = new SimpleDateFormat("EEE", Locale.getDefault());
        
        // Generate dates for last 7 days (oldest to newest)
        for (int i = 6; i >= 0; i--) {
            calendar.setTime(new Date());
            calendar.add(Calendar.DAY_OF_YEAR, -i);
            String dateKey = dateKeyFormat.format(calendar.getTime());
            String dayLabel = dayLabelFormat.format(calendar.getTime());
            dayLabels.add(dayLabel);
            
            final int dayIndex = 6 - i;
            
            // Fetch data for this date
            snapRef.child(dateKey).get()
                .addOnSuccessListener(daySnapshot -> {
                    process7DayData(daySnapshot, dateKey, dayIndex);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to fetch data for " + dateKey + ": " + e.getMessage());
                    addEmptyDayData(dayIndex);
                });
        }
        
        // Also listen for real-time updates on today's data
        String todayKey = dateKeyFormat.format(new Date());
        snapRef.child(todayKey).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                Log.d(TAG, "Today's data updated in real-time");
                process7DayData(snapshot, todayKey, 6); // Today is always index 6
            }
            
            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Real-time listener cancelled: " + error.getMessage());
            }
        });
    }
    
    /**
     * 📅 Process data from the 7-day rolling structure
     */
    private void process7DayData(DataSnapshot daySnapshot, String dateKey, int dayIndex) {
        Log.d(TAG, "Processing 7-day data for: " + dateKey + " (index: " + dayIndex + ")");
        
        List<com.example.master2.models.AppUsage> dayApps = new ArrayList<>();
        float totalHours = 0f;
        String totalText = "0 min";
        int appCount = 0;
        
        if (daySnapshot.exists()) {
            // Get total usage
            Long totalMs = daySnapshot.child("totalUsageMs").getValue(Long.class);
            if (totalMs != null && totalMs > 0) {
                totalHours = totalMs / (1000f * 60f * 60f);
                totalText = formatDuration(totalMs);
            }
            
            // Get app count
            Long count = daySnapshot.child("appCount").getValue(Long.class);
            if (count != null) {
                appCount = count.intValue();
            }
            
            // Get apps data
            DataSnapshot appsSnapshot = daySnapshot.child("apps");
            if (appsSnapshot.exists()) {
                for (DataSnapshot appSnapshot : appsSnapshot.getChildren()) {
                    com.example.master2.models.AppUsage app = appSnapshot.getValue(com.example.master2.models.AppUsage.class);
                    if (app != null && app.getUsageTime() > 0) {
                        dayApps.add(app);
                    }
                }
            }
            
            // Sort apps by usage time (highest first)
            Collections.sort(dayApps, (a, b) -> Long.compare(b.getUsageTime(), a.getUsageTime()));
        }
        
        // Store the data
        while (dailyApps.size() <= dayIndex) {
            dailyApps.add(new ArrayList<>());
        }
        while (dailyTotalTexts.size() <= dayIndex) {
            dailyTotalTexts.add("0 min");
        }
        
        dailyApps.set(dayIndex, dayApps);
        dailyTotalTexts.set(dayIndex, totalText);
        
        // Update UI for today (index 6)
        if (dayIndex == 6) {
            updateTodayUI(dayApps, totalText, appCount);
        }
        
        // Update chart
        updateChartWithAllData();
        
        Log.d(TAG, "✅ Processed " + appCount + " apps for " + dateKey + " with total: " + totalText);
    }
    
    private void processDaySnapshot(DataSnapshot dateSnapshot, String dateKey, int dayIndex) {
        Log.d(TAG, "Processing data for date: " + dateKey + ", dayIndex: " + dayIndex);
        
        List<com.example.master2.models.AppUsage> currentDayApps = new java.util.ArrayList<>();
        float totalUsageHours = 0f;
        String totalText = "0h 0m";
        
        if (dateSnapshot.exists()) {
            Log.d(TAG, "Found data for date: " + dateKey);
            
            // Parse apps data for this date
            if (dateSnapshot.child("apps").exists()) {
                for (DataSnapshot appSnapshot : dateSnapshot.child("apps").getChildren()) {
                    com.example.master2.models.AppUsage appUsage = appSnapshot.getValue(com.example.master2.models.AppUsage.class);
                    if (appUsage != null) {
                        currentDayApps.add(appUsage);
                        totalUsageHours += appUsage.getUsageTime() / (1000f * 60f * 60f); // Convert ms to hours
                    }
                }
            }
            
            // Get total text if available
            String storedTotal = dateSnapshot.child("totalText").getValue(String.class);
            if (storedTotal != null && !storedTotal.isEmpty()) {
                totalText = storedTotal;
            } else {
                // Calculate total text from usage time
                totalText = formatUsageTime((long)(totalUsageHours * 60 * 60 * 1000));
            }
        } else {
            Log.d(TAG, "No data found for date: " + dateKey);
        }
        
        // Ensure we have the right size for our lists
        while (dailyApps.size() <= dayIndex) {
            dailyApps.add(new java.util.ArrayList<>());
        }
        while (dailyTotalTexts.size() <= dayIndex) {
            dailyTotalTexts.add("0h 0m");
        }
        
        // Store the data for this day
        if (dayIndex < dailyApps.size()) {
            dailyApps.set(dayIndex, currentDayApps);
        } else {
            dailyApps.add(currentDayApps);
        }
        
        if (dayIndex < dailyTotalTexts.size()) {
            dailyTotalTexts.set(dayIndex, totalText);
        } else {
            dailyTotalTexts.add(totalText);
        }
        
        // Update UI if this is the latest day (today) or if we have valid data
        if (dayIndex == 6 || !currentDayApps.isEmpty()) { // Today is the 7th day (index 6) in a 7-day view
            updateUIWithDayData(currentDayApps, totalText, dayIndex);
        }
        
        // Also update the chart if we have enough data
        updateChartIfReady();
        
        Log.d(TAG, "Processed " + currentDayApps.size() + " apps for " + dateKey + " with total: " + totalText);
    }
    
    /**
     * Add empty data for days with no usage information
     */
    private void addEmptyDayData(int dayIndex) {
        List<com.example.master2.models.AppUsage> emptyApps = new java.util.ArrayList<>();
        String emptyTotal = "0h 0m";
        
        // Ensure we have the right size for our lists
        while (dailyApps.size() <= dayIndex) {
            dailyApps.add(new java.util.ArrayList<>());
        }
        while (dailyTotalTexts.size() <= dayIndex) {
            dailyTotalTexts.add("0h 0m");
        }
        
        if (dayIndex < dailyApps.size()) {
            dailyApps.set(dayIndex, emptyApps);
        } else {
            dailyApps.add(emptyApps);
        }
        
        if (dayIndex < dailyTotalTexts.size()) {
            dailyTotalTexts.set(dayIndex, emptyTotal);
        } else {
            dailyTotalTexts.add(emptyTotal);
        }
        
        Log.d(TAG, "Added empty data for day index: " + dayIndex);
    }
    
    /**
     * Update UI with data for a specific day
     */
    private void updateUIWithDayData(List<com.example.master2.models.AppUsage> dayApps, String totalText, int dayIndex) {
        runOnUiThread(() -> {
            // Update total time display
            if (tvTotalTime != null) {
                tvTotalTime.setText(totalText);
            }
            
            // Update app list
            if (adapter != null) {
                adapter.setData(dayApps);
            }
            
            // Update app count
            if (tvAppsCount != null) {
                tvAppsCount.setText(dayApps.size() + " apps");
            }
            
            // Update date label
            updateDateLabel(dayIndex);
            
            Log.d(TAG, "UI updated with " + dayApps.size() + " apps and total: " + totalText);
        });
    }
    
    /**
     * Format usage time from milliseconds to readable format
     */
    private String formatUsageTime(long usageTimeMs) {
        if (usageTimeMs <= 0) {
            return "0h 0m";
        }
        
        long hours = usageTimeMs / (1000 * 60 * 60);
        long minutes = (usageTimeMs % (1000 * 60 * 60)) / (1000 * 60);
        
        return hours + "h " + minutes + "m";
    }
    
    /**
     * Update chart when we have enough data ready
     */
    private void updateChartIfReady() {
        if (dailyTotalTexts.size() >= 7) { // We have data for 7 days
            Log.d(TAG, "Updating chart with " + dailyTotalTexts.size() + " days of data");
            
            // Convert total texts to bar values
            List<Float> bars = new java.util.ArrayList<>();
            for (String totalText : dailyTotalTexts) {
                float hours = parseHoursFromText(totalText);
                bars.add(hours);
            }
            
            // Create day labels for the chart (Mon, Tue, Wed, etc.)
            List<String> dayLabels = createDayLabels(bars.size());
            
            runOnUiThread(() -> {
                if (barChart != null) {
                    barChart.setData(bars, dayLabels);
                    selectedDayIndex = bars.size() - 1; // Select today by default
                    
                    // Setup bar click listener for date switching
                    barChart.setOnBarClickListener((index, value) -> {
                        Log.d(TAG, "Bar clicked: index=" + index + ", value=" + value);
                        selectedDayIndex = index;
                        
                        // Update app list for selected day
                        if (index < dailyApps.size()) {
                            List<com.example.master2.models.AppUsage> dayApps = dailyApps.get(index);
                            adapter.setData(dayApps);
                            Log.d(TAG, "Showing " + dayApps.size() + " apps for selected day");
                        }
                        
                        // Update total time for selected day
                        if (index < dailyTotalTexts.size()) {
                            tvTotalTime.setText(dailyTotalTexts.get(index));
                        }
                        
                        // Update date label for selected day
                        updateDateLabel(index);
                    });
                }
            });
        }
    }
    
    /**
     * Parse hours from text like "2h 30m" or "2 hr 30 min" -> 2.5f
     */
    private float parseHoursFromText(String totalText) {
        if (totalText == null || totalText.isEmpty() || totalText.equals("0 min")) {
            return 0f;
        }
        
        float totalHours = 0f;
        
        try {
            // Handle "hr" format
            if (totalText.contains("hr")) {
                String[] parts = totalText.split("hr");
                if (parts.length > 0 && !parts[0].trim().isEmpty()) {
                    totalHours = Float.parseFloat(parts[0].trim());
                }
            }
            // Handle "h" format (without "r")
            else if (totalText.contains("h") && !totalText.contains("hr")) {
                String[] parts = totalText.split("h");
                if (parts.length > 0 && !parts[0].trim().isEmpty()) {
                    totalHours = Float.parseFloat(parts[0].trim());
                }
            }
            
            // Handle minutes - both "min" and "m" formats
            if (totalText.contains("min")) {
                String minPart = totalText.substring(totalText.lastIndexOf(" ", totalText.indexOf("min")) + 1, totalText.indexOf("min")).trim();
                if (!minPart.isEmpty()) {
                    float minutes = Float.parseFloat(minPart);
                    totalHours += minutes / 60f;
                }
            } else if (totalText.contains("m") && !totalText.contains("min")) {
                // Handle "m" format (like "30m")
                String[] parts = totalText.split("m");
                if (parts.length > 1) {
                    String minPart = parts[parts.length - 2].trim();
                    // Get the last number before "m"
                    String[] minWords = minPart.split("\\s+");
                    if (minWords.length > 0) {
                        String lastWord = minWords[minWords.length - 1];
                        if (!lastWord.isEmpty()) {
                            float minutes = Float.parseFloat(lastWord);
                            totalHours += minutes / 60f;
                        }
                    }
                }
            }
            
            return totalHours;
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing hours from: " + totalText + ", error: " + e.getMessage());
            return 0f;
        }
    }

    private void parseSnapshot(DataSnapshot snapshot){
        if (snapshot == null) {
            Log.e(TAG, "Received null snapshot");
            return;
        }
        
        if (!snapshot.exists()) {
            Log.e(TAG, "Snapshot does not exist at path: " + snapshot.getRef().getPath());
            Toast.makeText(this, "No usage data found for this device", Toast.LENGTH_SHORT).show();
            return;
        }
        
        Log.d(TAG, "Parsing snapshot with " + snapshot.getChildrenCount() + " children");

        // Try to get the total text
        String total = snapshot.child("totalText").getValue(String.class);
        if (total != null) {
            tvTotalTime.setText(total);
            Log.d(TAG, "Total time: " + total);
        } else {
            Log.d(TAG, "No totalText field in snapshot");
        }

        // Parse bar chart data
        List<Float> bars = new java.util.ArrayList<>();
        if (snapshot.child("bars").exists()) {
            Log.d(TAG, "Processing bars data with " + snapshot.child("bars").getChildrenCount() + " entries");
            for (DataSnapshot b : snapshot.child("bars").getChildren()) {
                Double n = b.getValue(Double.class);
                if (n != null) {
                    bars.add(n.floatValue());
                    Log.d(TAG, "Bar value: " + n.floatValue());
                }
            }
        } else {
            Log.d(TAG, "No bars data in snapshot");
        }
        
        if (!bars.isEmpty()) {
            Log.d(TAG, "Setting " + bars.size() + " bars on chart");
            
            // Create day labels for the chart (Mon, Tue, Wed, etc.)
            List<String> dayLabels = createDayLabels(bars.size());
            barChart.setData(bars, dayLabels);
            
            // Set initial selected day index to today (last bar)
            selectedDayIndex = bars.size() - 1;
        } else {
            Log.d(TAG, "No bar data to display");
        }

        // Parse daily app usage data
        dailyApps.clear();
        dailyTotalTexts.clear();
        
        if (snapshot.child("dailyApps").exists()) {
            Log.d(TAG, "Processing daily apps with " + snapshot.child("dailyApps").getChildrenCount() + " days");
            for (DataSnapshot daySnap : snapshot.child("dailyApps").getChildren()) {
                List<com.example.master2.models.AppUsage> dayApps = new java.util.ArrayList<>();
                Log.d(TAG, "Day " + daySnap.getKey() + " has " + daySnap.getChildrenCount() + " apps");
                
                for (DataSnapshot a : daySnap.getChildren()) {
                    com.example.master2.models.AppUsage au = a.getValue(com.example.master2.models.AppUsage.class);
                    if (au != null) {
                        dayApps.add(au);
                    }
                }
                
                dailyApps.add(dayApps);
                Log.d(TAG, "Added " + dayApps.size() + " apps for day " + daySnap.getKey());
            }
        } else {
            Log.d(TAG, "No dailyApps data in snapshot");
        }
        
        if (snapshot.child("totalTexts").exists()) {
            Log.d(TAG, "Processing totalTexts with " + snapshot.child("totalTexts").getChildrenCount() + " entries");
            for (DataSnapshot t : snapshot.child("totalTexts").getChildren()) {
                String txt = t.getValue(String.class);
                if (txt != null) {
                    dailyTotalTexts.add(txt);
                    Log.d(TAG, "Total text: " + txt);
                }
            }
        } else {
            Log.d(TAG, "No totalTexts in snapshot");
        }

        // Display today's data and update initial date label
        int todayIdx = bars.size() - 1;
        if (!dailyApps.isEmpty() && todayIdx >= 0 && todayIdx < dailyApps.size()) {
            List<com.example.master2.models.AppUsage> todayApps = dailyApps.get(todayIdx);
            Log.d(TAG, "Setting adapter with " + todayApps.size() + " apps for today");
            adapter.setData(todayApps);
            
            // Update basic statistics
            updateBasicStatistics(todayApps, bars, todayIdx);
        } else {
            Log.d(TAG, "No today apps data to display");
            // Set default values for basic UI
            updateBasicStatistics(new java.util.ArrayList<>(), bars, todayIdx);
        }
        
        if (!dailyTotalTexts.isEmpty() && todayIdx >= 0 && todayIdx < dailyTotalTexts.size()) {
            String todayTotal = dailyTotalTexts.get(todayIdx);
            Log.d(TAG, "Setting today's total: " + todayTotal);
            tvTotalTime.setText(todayTotal);
        }
        
        // Update date label for today
        updateDateLabel(todayIdx);

        // Setup bar click listener for date switching
        barChart.setOnBarClickListener((index, value) -> {
            Log.d(TAG, "Bar clicked: index=" + index + ", value=" + value);
            selectedDayIndex = index; // Update selected day index
            selectedDayIndex = index; // Update selected day index
            
            // Update app list for selected day
            if (index < dailyApps.size()) {
                List<com.example.master2.models.AppUsage> dayApps = dailyApps.get(index);
                adapter.setData(dayApps);
                Log.d(TAG, "Showing " + dayApps.size() + " apps for selected day");
                
                // Update app count for selected day
                updateBasicStatistics(dayApps, bars, index);
            }
            
            // Update total time for selected day
            if (index < dailyTotalTexts.size()) {
                tvTotalTime.setText(dailyTotalTexts.get(index));
                Log.d(TAG, "Showing total time for selected day: " + dailyTotalTexts.get(index));
            }
            
            // Update date label for selected day
            updateDateLabel(index);
        });

        // Fallback for legacy snapshot format
        if (dailyApps.isEmpty()) {
            Log.d(TAG, "No daily apps data found, trying legacy format");
            List<com.example.master2.models.AppUsage> apps = new java.util.ArrayList<>();
            if (snapshot.child("apps").exists()) {
                Log.d(TAG, "Found legacy apps node with " + snapshot.child("apps").getChildrenCount() + " apps");
                for (DataSnapshot a : snapshot.child("apps").getChildren()) {
                    com.example.master2.models.AppUsage au = a.getValue(com.example.master2.models.AppUsage.class);
                    if (au != null) {
                        apps.add(au);
                        Log.d(TAG, "Added legacy app: " + au.getAppName() + " with " + au.getUsageTime() + "ms");
                    }
                }
                adapter.setData(apps);
                Log.d(TAG, "Set adapter with " + apps.size() + " legacy apps");
            } else {
                Log.d(TAG, "No legacy apps data found");
            }
        }
        
        if (adapter.getItemCount() == 0) {
            Log.d(TAG, "No app usage data to display after parsing");
            Toast.makeText(this, "No usage data available", Toast.LENGTH_SHORT).show();
        } else {
            Log.d(TAG, "Successfully displayed usage data with " + adapter.getItemCount() + " apps");
        }
    }
    
    /**
     * Update basic statistics display
     */
    private void updateBasicStatistics(List<com.example.master2.models.AppUsage> dayApps, List<Float> weeklyBars, int dayIndex) {
        try {
            // Calculate apps used for selected day
            int appsUsedDay = dayApps != null ? dayApps.size() : 0;
            tvAppsCount.setText(String.valueOf(appsUsedDay));
            
            Log.d(TAG, "📊 Updated basic statistics - Apps for day " + dayIndex + ": " + appsUsedDay);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error updating basic statistics: " + e.getMessage());
            // Set default values on error
            tvAppsCount.setText("0");
        }
    }
    
    /**
     * Create day labels for the chart (Mon, Tue, Wed, etc.)
     */
    private List<String> createDayLabels(int numberOfDays) {
        List<String> labels = new java.util.ArrayList<>();
        Calendar cal = Calendar.getInstance();
        
        // Start from (numberOfDays - 1) days ago to get to today
        cal.add(Calendar.DAY_OF_YEAR, -(numberOfDays - 1));
        
        for (int i = 0; i < numberOfDays; i++) {
            String dayName = dayFormat.format(cal.getTime());
            labels.add(dayName);
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        
        Log.d(TAG, "Created day labels: " + labels);
        return labels;
    }
    
    /**
     * Update the date label based on selected day index
     */
    private void updateDateLabel(int dayIndex) {
        if (dayIndex < 0) return;
        
        Calendar cal = Calendar.getInstance();
        Calendar today = Calendar.getInstance();
        
        // Calculate the date for the selected day index
        int totalDays = selectedDayIndex >= 0 ? Math.max(dailyApps.size(), 7) : 7;
        cal.add(Calendar.DAY_OF_YEAR, -(totalDays - 1 - dayIndex));
        
        String dateText = "Usage";  // Always show just "Usage" regardless of date
        
        tvDateLabel.setText(dateText);
        Log.d(TAG, "Updated date label to: " + dateText + " for day index " + dayIndex);
    }
    
    /**
     * Check if two calendars represent the same day
     */
    private boolean isSameDay(Calendar cal1, Calendar cal2) {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }
    
    /**
     * Check if cal1 is yesterday compared to cal2
     */
    private boolean isYesterday(Calendar cal1, Calendar cal2) {
        Calendar yesterday = (Calendar) cal2.clone();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        return isSameDay(cal1, yesterday);
    }
    
    /**
     * Setup period selection buttons
     */
    private void setupPeriodButtons() {
        // Only day button is active now, no need for week/month functionality
        btnDay.setOnClickListener(v -> {
            // Day is already selected and is the only option
            Log.d(TAG, "Day period already selected");
        });
    }
    
    /**
     * Update UI for today's data
     */
    private void updateTodayUI(List<com.example.master2.models.AppUsage> apps, String totalText, int appCount) {
        runOnUiThread(() -> {
            if (tvTotalTime != null) {
                tvTotalTime.setText(totalText);
            }
            if (tvAppsCount != null) {
                tvAppsCount.setText(appCount + " apps");
            }
            if (adapter != null) {
                adapter.setData(apps);
            }
            
            // Update date label for today
            if (tvDateLabel != null) {
                SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, MMM d", Locale.getDefault());
                tvDateLabel.setText(dateFormat.format(new Date()));
            }
        });
    }
    
    /**
     * Update chart with all 7 days of data
     */
    private void updateChartWithAllData() {
        if (dailyTotalTexts.size() >= 7) {
            List<Float> bars = new ArrayList<>();
            for (String totalText : dailyTotalTexts) {
                bars.add(parseHoursFromText(totalText));
            }
            
            List<String> dayLabels = createDayLabels(7);
            
            runOnUiThread(() -> {
                if (barChart != null) {
                    barChart.setData(bars, dayLabels);
                    selectedDayIndex = 6; // Today
                }
            });
        }
    }
    
    /**
     * Format duration from milliseconds
     */
    private String formatDuration(long millis) {
        if (millis < 1000) return "0 min";
        
        long hours = millis / (1000 * 60 * 60);
        long minutes = (millis % (1000 * 60 * 60)) / (1000 * 60);
        
        if (hours > 0) {
            return hours + " hr " + minutes + " min";
        } else {
            return minutes + " min";
        }
    }
    
}