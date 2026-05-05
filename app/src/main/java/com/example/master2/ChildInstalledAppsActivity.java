package com.example.master2;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageButton;
import android.text.Editable;
import android.text.TextWatcher;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.example.master2.utils.AppCategorizer;
import com.example.master2.models.AppWithUsage;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Activity to show all installed apps on child device with per-app timer
 * feature.
 */
public class ChildInstalledAppsActivity extends AppCompatActivity {
    private static final String TAG = "ChildInstalledApps";

    private String childDeviceId;
    private String childName;

    private RecyclerView rvApps;

    private View loadingOverlay;
    private TextView tvEmpty;
    private ImageView btnBack;

    private ImageButton btnClearSearch;

    private EnhancedAppsAdapter adapter;
    private List<Object> displayList = new ArrayList<>(); // Mixed: headers, AppWithUsage
    private List<AppWithUsage> allAppsWithUsage = new ArrayList<>();
    private Map<String, Long> usageDataMap = new HashMap<>(); // package -> usage time ms
    private Map<String, AppTimerData> appTimers = new HashMap<>();

    // 🚀 CACHE: Store usage data locally for instant loading
    private SharedPreferences usageCache;

    // 🔄 AUTO-REFRESH: Parent refreshes every 2 min 30 sec
    private Handler autoRefreshHandler = new Handler(Looper.getMainLooper());
    private Runnable autoRefreshRunnable;
    private static final long AUTO_REFRESH_INTERVAL = 150 * 1000; // 2 min 30 sec = 150 seconds

    // 🔍 Search and filter
    private EditText etSearch;
    private Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;
    private AppCategorizer.AppCategory selectedCategory = AppCategorizer.AppCategory.ALL;

    private ValueEventListener appsListener;
    private ValueEventListener timersListener;
    private ValueEventListener usageListener;
    private DatabaseReference appsRef;
    private DatabaseReference timersRef;
    private DatabaseReference usageRef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child_installed_apps);

        childDeviceId = getIntent().getStringExtra("childDeviceId");
        childName = getIntent().getStringExtra("childName"); // This might be device name

        if (childDeviceId == null) {
            Toast.makeText(this, "Error: No device selected", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 🔄 SYNC: Rebuild blocked status from Firebase history to ensure accuracy
        // This removes "ghost blocks" caused by legacy file corruption
        syncBlockStatusFromFirebase();

        // 🚀 CACHE: Initialize usage cache
        usageCache = getSharedPreferences("usage_cache_" + childDeviceId, MODE_PRIVATE);

        // 🚀 CACHE: Load cached usage data IMMEDIATELY for instant display
        loadCachedUsageData();

        initViews();
        loadChildNameFromFirebase(); // 🔧 Load actual child name
        setupRecyclerView();
        setupClickListeners();
        listenForApps();
        listenForTimers();
        listenForUsageData(); // 🆕 Load daily usage for top 5 (also updates cache)

        // 🔄 AUTO-REFRESH: Start periodic refresh every 2.5 minutes
        startAutoRefresh();
    }

    /**
     * 🔧 Load actual child name (userName) from Firebase
     */
    private void loadChildNameFromFirebase() {
        String parentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        if (parentUserId == null)
            return;

        DatabaseReference deviceRef = FirebaseDatabase.getInstance()
                .getReference("connected_devices")
                .child(parentUserId)
                .child(childDeviceId);

        deviceRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    String userName = snapshot.child("userName").getValue(String.class);
                    if (userName != null && !userName.isEmpty()) {
                        childName = userName; // Update with actual child name
                        TextView tvChildName = findViewById(R.id.tvChildName);
                        if (tvChildName != null) {
                            tvChildName.setText(childName);
                        }
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.w(TAG, "Failed to load child name: " + error.getMessage());
            }
        });
    }

    private void initViews() {
        rvApps = findViewById(R.id.rvInstalledApps);

        loadingOverlay = findViewById(R.id.loadingOverlay);
        tvEmpty = findViewById(R.id.tvEmpty);
        btnBack = findViewById(R.id.btnBack);

        etSearch = findViewById(R.id.etSearch);
        btnClearSearch = findViewById(R.id.btnClearSearch);

        TextView tvChildName = findViewById(R.id.tvChildName);
        if (tvChildName != null && childName != null) {
            tvChildName.setText(childName);
        }

        setupSearch();
    }

    private void setupRecyclerView() {
        adapter = new EnhancedAppsAdapter();
        rvApps.setLayoutManager(new LinearLayoutManager(this));
        rvApps.setAdapter(adapter);
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> finish());

        // Clear search button
        if (btnClearSearch != null) {
            btnClearSearch.setOnClickListener(v -> {
                etSearch.setText("");
                btnClearSearch.setVisibility(View.GONE);
            });
        }
    }

    /**
     * 🔍 Setup search with debouncing (300ms delay)
     */
    private void setupSearch() {
        if (etSearch == null)
            return;

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Show/hide clear button
                if (btnClearSearch != null) {
                    btnClearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (searchRunnable != null) {
                    searchHandler.removeCallbacks(searchRunnable);
                }

                searchRunnable = () -> rebuildDisplayList();
                searchHandler.postDelayed(searchRunnable, 300);
            }
        });
    }

    /**
     * 🚀 CACHE: Load usage data from local cache for INSTANT display
     * ⚡ OPTIMIZED: Checks date to ensure we don't show yesterday's data
     */
    private void loadCachedUsageData() {
        try {
            // Check if cache is from today
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            String today = sdf.format(new Date());
            String cacheDate = usageCache.getString("last_cache_date", "");

            if (!today.equals(cacheDate)) {
                Log.d(TAG, "🚀 CACHE: Cache is outdated (Old: " + cacheDate + ", New: " + today + "). Clearing.");
                usageCache.edit().clear().apply();
                usageDataMap.clear();
                return;
            }

            Map<String, ?> allCache = usageCache.getAll();
            usageDataMap.clear();

            int count = 0;
            for (Map.Entry<String, ?> entry : allCache.entrySet()) {
                if (entry.getValue() instanceof Long) {
                    usageDataMap.put(entry.getKey(), (Long) entry.getValue());
                    count++;
                }
            }

            if (count > 0) {
                Log.d(TAG, "🚀 CACHE: Loaded " + count + " cached usage entries - INSTANT!");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to load cached usage: " + e.getMessage());
        }
    }

    /**
     * 🆕 Listen for daily usage data from SUSAGE
     * Path:
     * susage_data/{deviceId}/weeklyData/{dateKey}/apps/{packageKey}/usageTimeMillis
     */
    private void listenForUsageData() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        final String today = sdf.format(new Date());

        Log.d(TAG, "📊 Setting up usage listener for date: " + today);

        usageRef = FirebaseDatabase.getInstance()
                .getReference("susage_data")
                .child(childDeviceId)
                .child("weeklyData")
                .child(today)
                .child("apps");

        // ⚡ OPTIMIZATION: Use get() first for faster one-time fetch, then listen
        usageRef.get().addOnSuccessListener(snapshot -> {
            processUsageSnapshot(snapshot, today);
        });

        usageListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                processUsageSnapshot(snapshot, today);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "❌ Usage data error: " + error.getMessage());
            }
        };

        usageRef.addValueEventListener(usageListener);
    }

    private void processUsageSnapshot(DataSnapshot snapshot, String today) {
        if (!snapshot.exists())
            return;

        usageDataMap.clear();

        // 🚀 CACHE: Prepare to save to cache
        SharedPreferences.Editor cacheEditor = usageCache.edit();
        cacheEditor.clear(); // Clear all old data
        cacheEditor.putString("last_cache_date", today); // Save today's date

        for (DataSnapshot appSnap : snapshot.getChildren()) {
            // Key is sanitized package name (dots replaced with underscores)
            String sanitizedKey = appSnap.getKey();
            // Get original package name from the data
            String packageName = appSnap.child("packageName").getValue(String.class);
            Long usageTime = appSnap.child("usageTimeMillis").getValue(Long.class);

            if (packageName == null) {
                // Fallback: convert sanitized key back (replace _ with .)
                packageName = sanitizedKey != null ? sanitizedKey.replace("_", ".") : null;
            }

            if (packageName != null && usageTime != null && usageTime > 0) {
                usageDataMap.put(packageName, usageTime);

                // 🚀 CACHE: Save to local cache
                cacheEditor.putLong(packageName, usageTime);
            }
        }

        // 🚀 CACHE: Commit all changes at once
        cacheEditor.apply();
        Log.d(TAG, "🚀 CACHE: Saved " + usageDataMap.size() + " entries for date " + today);

        rebuildDisplayList();
    }

    /**
     * 🆕 Rebuild display list with top 5 + filtered apps
     */
    private void rebuildDisplayList() {
        displayList.clear();

        Log.d(TAG, "=== REBUILD START ===");
        Log.d(TAG, "Total apps loaded: " + allAppsWithUsage.size());
        Log.d(TAG, "Usage data entries: " + usageDataMap.size());

        if (allAppsWithUsage.isEmpty()) {
            adapter.notifyDataSetChanged();
            updateEmptyState();
            return;
        }

        // Get search query
        String query = etSearch != null ? etSearch.getText().toString().toLowerCase().trim() : "";
        Log.d(TAG, "Search query: '" + query + "'");
        Log.d(TAG, "Selected category: " + selectedCategory.getDisplayName());

        // Filter by category and search
        List<AppWithUsage> filteredApps = new ArrayList<>();
        for (AppWithUsage app : allAppsWithUsage) {
            // Category filter - DISABLED
            // if (selectedCategory != AppCategorizer.AppCategory.ALL &&
            // app.getCategory() != selectedCategory) {
            // continue;
            // }

            // Search filter
            if (!query.isEmpty() &&
                    !app.getAppName().toLowerCase().contains(query) &&
                    !app.getPackageName().toLowerCase().contains(query)) {
                continue;
            }

            filteredApps.add(app);
        }

        Log.d(TAG, "Filtered apps: " + filteredApps.size());

        // Sort by usage time to get top 5
        List<AppWithUsage> sortedByUsage = new ArrayList<>(filteredApps);
        Collections.sort(sortedByUsage, (a, b) -> Long.compare(b.getUsageTimeMs(), a.getUsageTimeMs()));

        // Get top 5 with usage > 0
        List<AppWithUsage> top5 = new ArrayList<>();
        for (AppWithUsage app : sortedByUsage) {
            if (app.getUsageTimeMs() > 0 && top5.size() < 5) {
                app.setTopUsed(true);
                top5.add(app);
                Log.d(TAG, "Top " + (top5.size()) + ": " + app.getAppName() + " - " + app.getUsageTimeFormatted());
            }
        }

        Log.d(TAG, "Top 5 apps found: " + top5.size());

        // Add "Top 5 Most Used Today" section if we have data
        if (!top5.isEmpty()) {
            displayList.add("TOP 5 MOST USED TODAY"); // Section header
            displayList.addAll(top5);
            Log.d(TAG, "Added Top 5 section to display");
        } else {
            Log.w(TAG, "No apps with usage > 0 found!");
        }

        // Get remaining apps (not in top 5) and sort alphabetically
        List<AppWithUsage> remainingApps = new ArrayList<>();
        for (AppWithUsage app : filteredApps) {
            if (!top5.contains(app)) {
                app.setTopUsed(false);
                remainingApps.add(app);
            }
        }
        Collections.sort(remainingApps, (a, b) -> a.getAppName().compareToIgnoreCase(b.getAppName()));

        // Add "All Apps" section
        if (!remainingApps.isEmpty()) {
            displayList.add("ALL APPS (" + remainingApps.size() + ")"); // Section header
            displayList.addAll(remainingApps);
        }

        adapter.notifyDataSetChanged();
        updateEmptyState();

        Log.d(TAG, "=== REBUILD COMPLETE ===");
        Log.d(TAG, "Display list size: " + displayList.size() + " (top 5: " + top5.size() + ", regular: "
                + remainingApps.size() + ")");
    }

    private void listenForApps() {
        showLoading(true);

        appsRef = FirebaseDatabase.getInstance()
                .getReference("installed_apps")
                .child(childDeviceId);

        appsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                showLoading(false);
                parseAppsData(snapshot);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                showLoading(false);
                Log.e(TAG, "Firebase error: " + error.getMessage());
            }
        };

        appsRef.addValueEventListener(appsListener);
    }

    private void listenForTimers() {
        timersRef = FirebaseDatabase.getInstance()
                .getReference("app_timers")
                .child(childDeviceId);

        timersListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                appTimers.clear();
                for (DataSnapshot timerSnap : snapshot.getChildren()) {
                    try {
                        String packageName = timerSnap.child("packageName").getValue(String.class);
                        Long remainingMs = timerSnap.child("remainingTimeMillis").getValue(Long.class);
                        Long totalMs = timerSnap.child("totalTimeMillis").getValue(Long.class);
                        Boolean active = timerSnap.child("active").getValue(Boolean.class);

                        if (packageName != null && remainingMs != null) {
                            AppTimerData timer = new AppTimerData();
                            timer.packageName = packageName;
                            timer.remainingTimeMillis = remainingMs;
                            timer.totalTimeMillis = totalMs != null ? totalMs : remainingMs;
                            timer.active = active != null && active;
                            appTimers.put(packageName, timer);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error parsing timer: " + e.getMessage());
                    }
                }
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Timer listener error: " + error.getMessage());
            }
        };

        timersRef.addValueEventListener(timersListener);
    }

    private void parseAppsData(DataSnapshot snapshot) {
        allAppsWithUsage.clear();

        if (!snapshot.exists()) {
            updateEmptyState();

            rebuildDisplayList();
            return;
        }

        DataSnapshot appsSnapshot = snapshot.child("apps");
        for (DataSnapshot appSnapshot : appsSnapshot.getChildren()) {
            try {
                String packageName = appSnapshot.child("packageName").getValue(String.class);
                String appName = appSnapshot.child("appName").getValue(String.class);
                String iconBase64 = appSnapshot.child("iconBase64").getValue(String.class);
                Boolean isSystemApp = appSnapshot.child("isSystemApp").getValue(Boolean.class);

                if (packageName == null || appName == null)
                    continue;

                // Create AppWithUsage with context for smart categorization
                AppWithUsage app = new AppWithUsage(
                        ChildInstalledAppsActivity.this,
                        packageName,
                        appName,
                        iconBase64,
                        isSystemApp != null && isSystemApp);

                // Add usage data if available
                Long usageTime = usageDataMap.get(packageName);
                if (usageTime != null) {
                    app.setUsageTimeMs(usageTime);
                }

                // Add timer data if available
                AppTimerData timer = appTimers.get(packageName);
                if (timer != null) {
                    app.setHasTimer(true);
                    app.setTimerLimitMs(timer.totalTimeMillis);
                }

                allAppsWithUsage.add(app);
            } catch (Exception e) {
                Log.w(TAG, "Error parsing app: " + e.getMessage());
            }
        }

        rebuildDisplayList();
    }

    /**
     * 🔍 Update empty state based on current list
     */
    private void updateEmptyState() {
        View emptyState = findViewById(R.id.emptyState);
        if (displayList.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            rvApps.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            rvApps.setVisibility(View.VISIBLE);
        }
    }

    private void showTimerDialog(InstalledAppInfo app) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_app_timer);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        TextView tvSubtitle = dialog.findViewById(R.id.tvTimerSubtitle);
        NumberPicker npHours = dialog.findViewById(R.id.npHours);
        NumberPicker npMinutes = dialog.findViewById(R.id.npMinutes);
        Button btnDelete = dialog.findViewById(R.id.btnDeleteTimer);
        Button btnCancel = dialog.findViewById(R.id.btnCancel);
        Button btnOk = dialog.findViewById(R.id.btnOk);

        tvSubtitle.setText("This app timer for " + app.appName + " will reset at midnight");

        // Setup number pickers
        npHours.setMinValue(0);
        npHours.setMaxValue(12);
        npMinutes.setMinValue(0);
        npMinutes.setMaxValue(59);
        npMinutes.setValue(30);

        // Check if timer exists
        AppTimerData existingTimer = appTimers.get(app.packageName);
        if (existingTimer != null) {
            long remainingMs = existingTimer.remainingTimeMillis;
            int hours = (int) (remainingMs / (1000 * 60 * 60));
            int minutes = (int) ((remainingMs % (1000 * 60 * 60)) / (1000 * 60));
            npHours.setValue(hours);
            npMinutes.setValue(minutes);
            btnDelete.setVisibility(View.VISIBLE);
        }

        btnDelete.setOnClickListener(v -> {
            deleteTimer(app.packageName);
            dialog.dismiss();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnOk.setOnClickListener(v -> {
            int hours = npHours.getValue();
            int minutes = npMinutes.getValue();
            long totalMs = (hours * 60 + minutes) * 60 * 1000L;

            if (totalMs > 0) {
                setTimer(app.packageName, app.appName, totalMs);
                Toast.makeText(this, "Timer set for " + app.appName, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Please set a time", Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });

        dialog.show();
    }

    /**
     * 🔄 Trigger child device to upload fresh usage data
     */
    private void triggerUsageRefresh() {
        DatabaseReference refreshRef = FirebaseDatabase.getInstance()
                .getReference("susage_update_requests")
                .child(childDeviceId);

        Map<String, Object> request = new HashMap<>();
        request.put("requestUpdate", true);
        request.put("timestamp", System.currentTimeMillis());
        request.put("requestedBy", "parent");

        refreshRef.setValue(request)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Usage refresh triggered for child device");
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Failed to trigger refresh: " + e.getMessage());
                });
    }

    private void setTimer(String packageName, String appName, long totalTimeMs) {
        String safeKey = packageName.replaceAll("[.#$\\[\\]/]", "_");

        Map<String, Object> timerData = new HashMap<>();
        timerData.put("packageName", packageName);
        timerData.put("appName", appName);
        timerData.put("totalTimeMillis", totalTimeMs);
        timerData.put("remainingTimeMillis", totalTimeMs);
        timerData.put("active", true);
        timerData.put("createdAt", System.currentTimeMillis());
        timerData.put("lastUpdated", System.currentTimeMillis());

        timersRef.child(safeKey).setValue(timerData)
                .addOnSuccessListener(aVoid -> Log.d(TAG, "Timer set for " + packageName))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to set timer: " + e.getMessage());
                    Toast.makeText(this, "Failed to set timer", Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * 🔄 AUTO-REFRESH: Reload usage data every 2.5 minutes
     */
    private void startAutoRefresh() {
        autoRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                // Trigger child to upload fresh data
                triggerUsageRefresh();
                Log.d(TAG, "🔄 Auto-refresh: Requested fresh usage data");

                // Schedule next refresh
                autoRefreshHandler.postDelayed(this, AUTO_REFRESH_INTERVAL);
            }
        };

        // Start first refresh after 2.5 minutes
        autoRefreshHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL);
        Log.d(TAG, "✅ Auto-refresh started (every 2 min 30 sec)");
    }

    /**
     * 🛑 Stop auto-refresh timer
     */
    private void stopAutoRefresh() {
        if (autoRefreshHandler != null && autoRefreshRunnable != null) {
            autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
            Log.d(TAG, "🛑 Auto-refresh stopped");
        }
    }

    private void deleteTimer(String packageName) {
        String safeKey = packageName.replaceAll("[.#$\\[\\]/]", "_");

        timersRef.child(safeKey).removeValue()
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Timer deleted", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "Timer deleted for " + packageName);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to delete timer: " + e.getMessage());
                    Toast.makeText(this, "Failed to delete timer", Toast.LENGTH_SHORT).show();
                });
    }

    private void requestChildRefresh() {
        DatabaseReference cmdRef = FirebaseDatabase.getInstance()
                .getReference("child_commands")
                .child(childDeviceId);

        cmdRef.child("refreshApps").setValue(System.currentTimeMillis())
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "Refresh requested", Toast.LENGTH_SHORT).show();
                    showLoading(false);
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to request refresh", Toast.LENGTH_SHORT).show();
                    showLoading(false);
                });
    }

    private void showLoading(boolean show) {
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private String formatTime(long millis) {
        long totalMinutes = millis / (1000 * 60);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Remove Firebase listeners
        if (appsListener != null && appsRef != null) {
            appsRef.removeEventListener(appsListener);
        }
        if (timersListener != null && timersRef != null) {
            timersRef.removeEventListener(timersListener);
        }
        if (usageListener != null && usageRef != null) {
            usageRef.removeEventListener(usageListener);
        }

        // Stop auto-refresh timer
        stopAutoRefresh();

        Log.d(TAG, "ChildInstalledAppsActivity destroyed");
    }

    // Data classes
    static class InstalledAppInfo {
        String packageName;
        String appName;
        String iconBase64;
        boolean isSystemApp;
    }

    static class AppTimerData {
        String packageName;
        long totalTimeMillis;
        long remainingTimeMillis;
        boolean active;
    }

    // Adapter
    class InstalledAppsAdapter extends RecyclerView.Adapter<InstalledAppsAdapter.ViewHolder> {
        private final List<InstalledAppInfo> apps;

        InstalledAppsAdapter(List<InstalledAppInfo> apps) {
            this.apps = apps;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_installed_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            InstalledAppInfo app = apps.get(position);

            holder.tvAppName.setText(app.appName);

            // Decode and show icon
            if (app.iconBase64 != null && !app.iconBase64.isEmpty()) {
                try {
                    byte[] decodedBytes = Base64.decode(app.iconBase64, Base64.NO_WRAP);
                    Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                    holder.imgIcon.setImageBitmap(bitmap);
                } catch (Exception e) {
                    holder.imgIcon.setImageResource(R.mipmap.ic_launcher);
                }
            } else {
                holder.imgIcon.setImageResource(R.mipmap.ic_launcher);
            }

            // Timer display
            AppTimerData timer = appTimers.get(app.packageName);
            if (timer != null && timer.remainingTimeMillis > 0) {
                holder.tvRemainingTime.setVisibility(View.VISIBLE);
                holder.tvRemainingTime.setText(formatTime(timer.remainingTimeMillis));
            } else {
                holder.tvRemainingTime.setVisibility(View.GONE);
            }

            // "Set Time Limit" click
            holder.tvSetTimeLimit.setOnClickListener(v -> showTimerDialog(app));

            // Check if app is blocked - set initial state WITHOUT triggering listener
            boolean isBlocked = isAppBlocked(app.packageName);
            holder.switchBlock.setOnCheckedChangeListener(null); // Clear listener first
            holder.switchBlock.setChecked(isBlocked);

            // Use OnClickListener instead of OnCheckedChangeListener to avoid spam
            holder.switchBlock.setOnClickListener(v -> {
                boolean currentlyBlocked = isAppBlocked(app.packageName);

                if (currentlyBlocked) {
                    // App is currently blocked, user wants to unblock
                    showUnblockConfirmation(app.packageName, app.appName, () -> {
                        unblockApp(app.packageName);
                    }, () -> {
                        // User cancelled - keep switch in blocked position
                        holder.switchBlock.setChecked(true);
                    });
                } else {
                    // App is currently unblocked, user wants to block
                    showBlockConfirmation(app.packageName, app.appName, () -> {
                        blockApp(app.packageName, app.appName);
                    }, () -> {
                        // User cancelled - keep switch in unblocked position
                        holder.switchBlock.setChecked(false);
                    });
                }
            });
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView imgIcon;
            TextView tvAppName;
            TextView tvSetTimeLimit;
            TextView tvRemainingTime;
            androidx.appcompat.widget.SwitchCompat switchBlock;

            ViewHolder(View itemView) {
                super(itemView);
                imgIcon = itemView.findViewById(R.id.imgAppIcon);
                tvAppName = itemView.findViewById(R.id.tvAppName);
                tvSetTimeLimit = itemView.findViewById(R.id.tvSetTimeLimit);
                tvRemainingTime = itemView.findViewById(R.id.tvRemainingTime);
                switchBlock = itemView.findViewById(R.id.switchBlock);
            }
        }
    }

    // Blocking functionality
    private DatabaseReference blockCommandsRef;

    private boolean isAppBlocked(String packageName) {
        // Check if app is in blocked_apps SharedPreferences (device specific)
        android.content.SharedPreferences prefs = getSharedPreferences("blocked_apps_" + childDeviceId, MODE_PRIVATE);
        return prefs.getBoolean(packageName, false);
    }

    private void blockApp(String packageName, String appName) {
        if (blockCommandsRef == null) {
            blockCommandsRef = FirebaseDatabase.getInstance()
                    .getReference("block_commands")
                    .child(childDeviceId);
        }

        // Create BlockCommand same as ParentDashboardActivity does
        String commandId = "cmd_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 1000);

        Map<String, Object> blockCommand = new HashMap<>();
        blockCommand.put("commandId", commandId);
        blockCommand.put("targetDeviceId", childDeviceId);
        blockCommand.put("controllerDeviceId", "parent"); // Sent from parent app
        blockCommand.put("packageName", packageName);
        blockCommand.put("appName", appName);
        blockCommand.put("blockStatus", true); // Block
        blockCommand.put("timestamp", System.currentTimeMillis());
        blockCommand.put("executed", false);

        blockCommandsRef.child(commandId).setValue(blockCommand)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, appName + " blocked", Toast.LENGTH_SHORT).show();
                    // Update local cache
                    cacheBlockStatus(packageName, true);
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to block app", Toast.LENGTH_SHORT).show();
                });
    }

    private void unblockApp(String packageName) {
        if (blockCommandsRef == null) {
            blockCommandsRef = FirebaseDatabase.getInstance()
                    .getReference("block_commands")
                    .child(childDeviceId);
        }

        // Create BlockCommand for unblock
        String commandId = "cmd_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 1000);

        Map<String, Object> blockCommand = new HashMap<>();
        blockCommand.put("commandId", commandId);
        blockCommand.put("targetDeviceId", childDeviceId);
        blockCommand.put("controllerDeviceId", "parent");
        blockCommand.put("packageName", packageName);
        blockCommand.put("appName", packageName); // Don't have appName here
        blockCommand.put("blockStatus", false); // Unblock
        blockCommand.put("timestamp", System.currentTimeMillis());
        blockCommand.put("executed", false);

        blockCommandsRef.child(commandId).setValue(blockCommand)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(this, "App unblocked", Toast.LENGTH_SHORT).show();
                    cacheBlockStatus(packageName, false);
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to unblock app", Toast.LENGTH_SHORT).show();
                });
    }

    private void cacheBlockStatus(String packageName, boolean isBlocked) {
        // Use device specific SharedPreferences
        android.content.SharedPreferences prefs = getSharedPreferences("blocked_apps_" + childDeviceId, MODE_PRIVATE);
        prefs.edit().putBoolean(packageName, isBlocked).apply();
    }

    private void showBlockConfirmation(String packageName, String appName, Runnable onConfirm, Runnable onCancel) {
        // Create context with forced light theme to prevent dark mode text issues
        android.view.ContextThemeWrapper themedContext = new android.view.ContextThemeWrapper(this,
                R.style.AlertDialogCustom);

        new androidx.appcompat.app.AlertDialog.Builder(themedContext)
                .setTitle("Block App?")
                .setMessage("Do you wanna block " + appName + "?")
                .setPositiveButton("Block", (dialog, which) -> {
                    if (onConfirm != null)
                        onConfirm.run();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    if (onCancel != null)
                        onCancel.run();
                })
                .setCancelable(false)
                .show();
    }

    private void showUnblockConfirmation(String packageName, String appName, Runnable onConfirm, Runnable onCancel) {
        // Create context with forced light theme to prevent dark mode text issues
        android.view.ContextThemeWrapper themedContext = new android.view.ContextThemeWrapper(this,
                R.style.AlertDialogCustom);

        new androidx.appcompat.app.AlertDialog.Builder(themedContext)
                .setTitle("Unblock App?")
                .setMessage("Do you wanna unblock " + appName + "?")
                .setPositiveButton("Unblock", (dialog, which) -> {
                    if (onConfirm != null)
                        onConfirm.run();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    if (onCancel != null)
                        onCancel.run();
                })
                .setCancelable(false)
                .show();
    }

    // \ud83c\udd95 Enhanced Adapter with multiple view types
    class EnhancedAppsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int VIEW_TYPE_HEADER = 0;
        private static final int VIEW_TYPE_TOP_USED = 1;
        private static final int VIEW_TYPE_REGULAR = 2;

        @Override
        public int getItemViewType(int position) {
            Object item = displayList.get(position);
            if (item instanceof String) {
                return VIEW_TYPE_HEADER;
            } else if (item instanceof AppWithUsage) {
                AppWithUsage app = (AppWithUsage) item;
                return app.isTopUsed() ? VIEW_TYPE_TOP_USED : VIEW_TYPE_REGULAR;
            }
            return VIEW_TYPE_REGULAR;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            switch (viewType) {
                case VIEW_TYPE_HEADER:
                    View headerView = inflater.inflate(R.layout.item_app_section_header, parent, false);
                    return new HeaderViewHolder(headerView);
                case VIEW_TYPE_TOP_USED:
                    View topView = inflater.inflate(R.layout.item_top_used_app, parent, false);
                    return new TopUsedViewHolder(topView);
                default:
                    View regularView = inflater.inflate(R.layout.item_installed_app, parent, false);
                    return new RegularViewHolder(regularView);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object item = displayList.get(position);

            if (holder instanceof HeaderViewHolder) {
                ((HeaderViewHolder) holder).bind((String) item);
            } else if (holder instanceof TopUsedViewHolder) {
                int rank = 1;
                for (int i = 0; i <= position; i++) {
                    Object obj = displayList.get(i);
                    if (obj instanceof AppWithUsage && ((AppWithUsage) obj).isTopUsed()) {
                        if (i == position)
                            break;
                        rank++;
                    }
                }
                ((TopUsedViewHolder) holder).bind((AppWithUsage) item, rank);
            } else if (holder instanceof RegularViewHolder) {
                ((RegularViewHolder) holder).bind((AppWithUsage) item);
            }
        }

        @Override
        public int getItemCount() {
            return displayList.size();
        }

        // Header ViewHolder
        class HeaderViewHolder extends RecyclerView.ViewHolder {
            TextView tvSectionTitle;
            TextView tvLimitHeader;
            TextView tvBlockingHeader;

            HeaderViewHolder(View itemView) {
                super(itemView);
                tvSectionTitle = itemView.findViewById(R.id.tvSectionTitle);
                tvLimitHeader = itemView.findViewById(R.id.tvLimitHeader);
                tvBlockingHeader = itemView.findViewById(R.id.tvBlockingHeader);
            }

            void bind(String title) {
                tvSectionTitle.setText(title);

                // 🎨 Add icons based on title content
                if (title.contains("TOP 5")) {
                    tvSectionTitle.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_trend_chart_circle, 0, 0, 0);
                    tvSectionTitle.setCompoundDrawablePadding(16); // 16px padding

                    // Show column headers for Top 5 section
                    if (tvLimitHeader != null)
                        tvLimitHeader.setVisibility(View.VISIBLE);
                    if (tvBlockingHeader != null)
                        tvBlockingHeader.setVisibility(View.VISIBLE);
                } else if (title.contains("ALL APPS")) {
                    tvSectionTitle.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_apps_phone_premium, 0, 0, 0);
                    tvSectionTitle.setCompoundDrawablePadding(16);

                    // Hide column headers for All Apps section
                    if (tvLimitHeader != null)
                        tvLimitHeader.setVisibility(View.GONE);
                    if (tvBlockingHeader != null)
                        tvBlockingHeader.setVisibility(View.GONE);
                } else {
                    tvSectionTitle.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
                    if (tvLimitHeader != null)
                        tvLimitHeader.setVisibility(View.GONE);
                    if (tvBlockingHeader != null)
                        tvBlockingHeader.setVisibility(View.GONE);
                }
            }
        }

        // Top 5 ViewHolder
        class TopUsedViewHolder extends RecyclerView.ViewHolder {
            TextView tvRank, tvAppName, tvUsageTime, tvSetLimit;
            ImageView ivIcon;
            androidx.appcompat.widget.SwitchCompat switchBlock;

            TopUsedViewHolder(View itemView) {
                super(itemView);
                tvRank = itemView.findViewById(R.id.tvRank);
                tvAppName = itemView.findViewById(R.id.tvAppName);
                tvUsageTime = itemView.findViewById(R.id.tvUsageTime);
                tvSetLimit = itemView.findViewById(R.id.tvSetLimit);
                ivIcon = itemView.findViewById(R.id.ivIcon);
                switchBlock = itemView.findViewById(R.id.switchBlock);
            }

            void bind(AppWithUsage app, int rank) {
                tvRank.setText(String.valueOf(rank));
                tvAppName.setText(app.getAppName());
                tvUsageTime.setText(app.getUsageTimeFormatted() + " today");

                // Decode icon
                if (app.getIconBase64() != null && !app.getIconBase64().isEmpty()) {
                    try {
                        byte[] decodedBytes = Base64.decode(app.getIconBase64(), Base64.NO_WRAP);
                        Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                        ivIcon.setImageBitmap(bitmap);
                    } catch (Exception e) {
                        ivIcon.setImageResource(R.mipmap.ic_launcher);
                    }
                } else {
                    ivIcon.setImageResource(R.mipmap.ic_launcher);
                }

                tvSetLimit.setOnClickListener(v -> showTimerDialogForApp(app));

                // Block switch logic
                boolean isBlocked = isAppBlocked(app.getPackageName());
                switchBlock.setOnCheckedChangeListener(null);
                switchBlock.setChecked(isBlocked);

                switchBlock.setOnClickListener(v -> {
                    // CRITICAL: Read actual state from cache IMMEDIATELY, not from switch visual
                    final boolean currentlyBlocked = isAppBlocked(app.getPackageName());

                    // Prevent switch from changing until user confirms
                    switchBlock.setChecked(currentlyBlocked);

                    if (currentlyBlocked) {
                        // Currently blocked -> User wants to UNBLOCK
                        showUnblockConfirmation(app.getPackageName(), app.getAppName(),
                                () -> {
                                    unblockApp(app.getPackageName());
                                    switchBlock.setChecked(false);
                                },
                                () -> {
                                    // User cancelled - keep in blocked state
                                    switchBlock.setChecked(true);
                                });
                    } else {
                        // Currently unblocked -> User wants to BLOCK
                        showBlockConfirmation(app.getPackageName(), app.getAppName(),
                                () -> {
                                    blockApp(app.getPackageName(), app.getAppName());
                                    switchBlock.setChecked(true);
                                },
                                () -> {
                                    // User cancelled - keep in unblocked state
                                    switchBlock.setChecked(false);
                                });
                    }
                });
            }
        }

        // Regular App ViewHolder
        class RegularViewHolder extends RecyclerView.ViewHolder {
            ImageView imgIcon;
            TextView tvAppName, tvSetTimeLimit, tvRemainingTime;
            androidx.appcompat.widget.SwitchCompat switchBlock;

            RegularViewHolder(View itemView) {
                super(itemView);
                imgIcon = itemView.findViewById(R.id.imgAppIcon);
                tvAppName = itemView.findViewById(R.id.tvAppName);
                tvSetTimeLimit = itemView.findViewById(R.id.tvSetTimeLimit);
                tvRemainingTime = itemView.findViewById(R.id.tvRemainingTime);
                switchBlock = itemView.findViewById(R.id.switchBlock);
            }

            void bind(AppWithUsage app) {
                tvAppName.setText(app.getAppName());

                // Decode icon
                if (app.getIconBase64() != null && !app.getIconBase64().isEmpty()) {
                    try {
                        byte[] decodedBytes = Base64.decode(app.getIconBase64(), Base64.NO_WRAP);
                        Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                        imgIcon.setImageBitmap(bitmap);
                    } catch (Exception e) {
                        imgIcon.setImageResource(R.mipmap.ic_launcher);
                    }
                } else {
                    imgIcon.setImageResource(R.mipmap.ic_launcher);
                }

                // Timer display
                AppTimerData timer = appTimers.get(app.getPackageName());
                if (timer != null && timer.remainingTimeMillis > 0) {
                    tvRemainingTime.setVisibility(View.VISIBLE);
                    tvRemainingTime.setText(formatTime(timer.remainingTimeMillis));
                } else {
                    tvRemainingTime.setVisibility(View.GONE);
                }

                tvSetTimeLimit.setOnClickListener(v -> showTimerDialogForApp(app));

                // Block switch
                boolean isBlocked = isAppBlocked(app.getPackageName());
                switchBlock.setOnCheckedChangeListener(null);
                switchBlock.setChecked(isBlocked);

                switchBlock.setOnClickListener(v -> {
                    // CRITICAL: Read actual state from cache IMMEDIATELY, not from switch visual
                    final boolean currentlyBlocked = isAppBlocked(app.getPackageName());

                    // Prevent switch from changing until user confirms
                    switchBlock.setChecked(currentlyBlocked);

                    if (currentlyBlocked) {
                        // Currently blocked -> User wants to UNBLOCK
                        showUnblockConfirmation(app.getPackageName(), app.getAppName(),
                                () -> {
                                    unblockApp(app.getPackageName());
                                    switchBlock.setChecked(false);
                                },
                                () -> {
                                    // User cancelled - keep in blocked state
                                    switchBlock.setChecked(true);
                                });
                    } else {
                        // Currently unblocked -> User wants to BLOCK
                        showBlockConfirmation(app.getPackageName(), app.getAppName(),
                                () -> {
                                    blockApp(app.getPackageName(), app.getAppName());
                                    switchBlock.setChecked(true);
                                },
                                () -> {
                                    // User cancelled - keep in unblocked state
                                    switchBlock.setChecked(false);
                                });
                    }
                });
            }
        }
    }

    private void showTimerDialogForApp(AppWithUsage app) {
        // Reuse existing timer dialog logic
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_app_timer);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        TextView tvSubtitle = dialog.findViewById(R.id.tvTimerSubtitle);
        NumberPicker npHours = dialog.findViewById(R.id.npHours);
        NumberPicker npMinutes = dialog.findViewById(R.id.npMinutes);
        Button btnDelete = dialog.findViewById(R.id.btnDeleteTimer);
        Button btnCancel = dialog.findViewById(R.id.btnCancel);
        Button btnOk = dialog.findViewById(R.id.btnOk);

        tvSubtitle.setText("This app timer for " + app.getAppName() + " will reset at midnight");

        npHours.setMinValue(0);
        npHours.setMaxValue(12);
        npMinutes.setMinValue(0);
        npMinutes.setMaxValue(59);
        npMinutes.setValue(30);

        AppTimerData existingTimer = appTimers.get(app.getPackageName());
        if (existingTimer != null) {
            long remainingMs = existingTimer.remainingTimeMillis;
            int hours = (int) (remainingMs / (1000 * 60 * 60));
            int minutes = (int) ((remainingMs % (1000 * 60 * 60)) / (1000 * 60));
            npHours.setValue(hours);
            npMinutes.setValue(minutes);
            btnDelete.setVisibility(View.VISIBLE);
        }

        btnDelete.setOnClickListener(v -> {
            deleteTimer(app.getPackageName());
            dialog.dismiss();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnOk.setOnClickListener(v -> {
            int hours = npHours.getValue();
            int minutes = npMinutes.getValue();
            long totalMs = (hours * 60 + minutes) * 60 * 1000L;

            if (totalMs > 0) {
                setTimer(app.getPackageName(), app.getAppName(), totalMs);
                Toast.makeText(this, "Timer set for " + app.getAppName(), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Please set a time", Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });

        dialog.show();
    }

    /**
     * 🔄 MIGRATION: Copy blocked apps from old "blocked_apps" file to new
     * "blocked_apps_{deviceId}"
     * This restores the user's previous configuration which was stored globally.
     */
    /**
     * 🔄 SYNC: Rebuild blocked status from Firebase history
     * This is the SOURCE OF TRUTH. It ignores local corruption and fetches
     * what we actually commanded.
     */
    private void syncBlockStatusFromFirebase() {
        if (childDeviceId == null)
            return;

        DatabaseReference commandsRef = FirebaseDatabase.getInstance()
                .getReference("block_commands")
                .child(childDeviceId);

        commandsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                // Map to track latest status: Package -> IsBlocked
                Map<String, Boolean> trueStatus = new HashMap<>();

                // Map to track timestamp to ensure we use latest command
                Map<String, Long> latestTimestamps = new HashMap<>();

                for (DataSnapshot cmdSnap : snapshot.getChildren()) {
                    try {
                        String packageName = cmdSnap.child("packageName").getValue(String.class);
                        Boolean blocked = cmdSnap.child("blockStatus").getValue(Boolean.class);
                        Long timestamp = cmdSnap.child("timestamp").getValue(Long.class);

                        if (packageName != null && blocked != null && timestamp != null) {
                            Long lastTime = latestTimestamps.get(packageName);
                            if (lastTime == null || timestamp > lastTime) {
                                trueStatus.put(packageName, blocked);
                                latestTimestamps.put(packageName, timestamp);
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error parsing command during sync: " + e.getMessage());
                    }
                }

                // 2. Overwrite Local Cache with Truth
                android.content.SharedPreferences prefs = getSharedPreferences("blocked_apps_" + childDeviceId,
                        MODE_PRIVATE);
                android.content.SharedPreferences.Editor editor = prefs.edit();
                editor.clear(); // Wipe potential corruption/ghosts

                for (Map.Entry<String, Boolean> entry : trueStatus.entrySet()) {
                    if (entry.getValue()) {
                        editor.putBoolean(entry.getKey(), true);
                    }
                }
                editor.apply();

                // 3. Refresh Adapter
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }

                Log.d(TAG, "✅ Synced block status from Cloud. Ghost blocks removed for: " + childDeviceId);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Sync failed: " + error.getMessage());
            }
        });
    }
}
