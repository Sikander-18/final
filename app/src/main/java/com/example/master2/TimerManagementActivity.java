package com.example.master2;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class TimerManagementActivity extends AppCompatActivity {

    private static final String TAG = "TimerManagement";

    // UI Elements
    private TextView tvActiveTimer;
    private TextView tvTimerStatus;
    private EditText etTimerHours;
    private EditText etTimerMinutes;
    private Button btnUpdateApps;
    private Button btnSetTimer;
    private Button btnClearTimer;
    private Button btnRefreshTimer;
    private TextView tvInstructionText;
    private TextView tvAssignedApps;
    private BottomNavigationView bottomNavigation;

    // Data
    private String currentChildDeviceId;
    private String currentChildDeviceName;
    private Set<String> selectedApps = new HashSet<>();
    private Map<String, String> appNameMap = new HashMap<>(); // Map to store app names for selected apps (package ->
                                                              // app name)

    // Firebase
    private DatabaseReference limiterRef;
    private DatabaseReference currentLimiterDeviceRef;
    private ValueEventListener limiterListener;

    // Timer monitoring
    private Handler timerHandler;
    private Runnable timerRunnable;
    private boolean isTimerActive = false;
    private boolean hasTimerBeenSet = false; // New flag: once timer is set, button stays disabled until manually
                                             // cleared

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_timer_management);

        Log.d(TAG, "TimerManagementActivity created");

        // Get device info from intent
        currentChildDeviceId = getIntent().getStringExtra("deviceId");
        currentChildDeviceName = getIntent().getStringExtra("deviceName");

        if (currentChildDeviceId == null) {
            Toast.makeText(this, "No device selected", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 🔧 DEVICE-SPECIFIC STATE ISOLATION: Clear all previous device data
        clearAllDeviceState();

        initializeViews();
        initializeFirebase();
        setupBottomNavigation();
        setupTimerInputs();
        setupButtons();
        Log.d(TAG, "About to load timer state, current selectedApps: " + selectedApps.toString());
        loadTimerState();
        // Removed real-time timer monitoring - using refresh button instead
    }

    private void initializeViews() {
        tvActiveTimer = findViewById(R.id.tvActiveTimer);
        tvTimerStatus = findViewById(R.id.tvTimerStatus);
        etTimerHours = findViewById(R.id.etTimerHours);
        etTimerMinutes = findViewById(R.id.etTimerMinutes);
        btnUpdateApps = findViewById(R.id.btnUpdateApps);
        btnSetTimer = findViewById(R.id.btnSetTimer);
        btnClearTimer = findViewById(R.id.btnClearTimer);
        btnRefreshTimer = findViewById(R.id.btnRefreshTimer);
        tvInstructionText = findViewById(R.id.tvInstructionText);
        tvAssignedApps = findViewById(R.id.tvAssignedApps);
        bottomNavigation = findViewById(R.id.bottom_navigation_timer);

        Log.d(TAG, "Views initialized for device: " + currentChildDeviceName);
    }

    private void initializeFirebase() {
        limiterRef = FirebaseDatabase.getInstance().getReference("usage_limiters");
        Log.d(TAG, "Firebase initialized");
    }

    /**
     * 🔧 DEVICE-SPECIFIC STATE ISOLATION
     * Clear all device-specific state to prevent data mixing when switching devices
     */
    private void clearAllDeviceState() {
        Log.d(TAG, "🧹 Clearing all device-specific state for device isolation");

        // Clear app selection data
        selectedApps.clear();
        appNameMap.clear(); // Clear stored app names

        // Clear timer state flags
        isTimerActive = false;
        hasTimerBeenSet = false;

        // Clear any existing timer monitoring
        if (timerHandler != null && timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }

        // Clear any Firebase listeners
        if (limiterListener != null && currentLimiterDeviceRef != null) {
            currentLimiterDeviceRef.removeEventListener(limiterListener);
            limiterListener = null;
            currentLimiterDeviceRef = null;
        }

        // Reset UI input fields
        if (etTimerHours != null)
            etTimerHours.setText("");
        if (etTimerMinutes != null)
            etTimerMinutes.setText("");

        // Reset UI to show inactive state (only if views are initialized)
        if (tvActiveTimer != null) {
            displayInactiveTimer();
        }

        // Update button states and text to reflect cleared state (only if views are
        // initialized)
        if (btnSetTimer != null && btnUpdateApps != null) {
            updateButtonStates();
            updateAppsButtonText();
        }

        // Update assigned apps display (only if views are initialized)
        if (tvAssignedApps != null) {
            updateAssignedAppsDisplay();
        }

        Log.d(TAG, "✅ Device state cleared for: " + currentChildDeviceId);
    }

    private void setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener(new BottomNavigationView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                int itemId = item.getItemId();

                if (itemId == R.id.nav_home) {
                    // Return to parent dashboard and show home content
                    Intent intent = new Intent();
                    intent.putExtra("selected_tab", "home");
                    setResult(RESULT_OK, intent);
                    finish();
                    return true;
                } else if (itemId == R.id.nav_timer) {
                    // Already on timer page
                    return true;
                } else if (itemId == R.id.nav_settings) {
                    // Return to parent dashboard and show settings content
                    Intent intent = new Intent();
                    intent.putExtra("selected_tab", "settings");
                    setResult(RESULT_OK, intent);
                    finish();
                    return true;
                }

                return false;
            }
        });

        // Set Timer as selected
        bottomNavigation.setSelectedItemId(R.id.nav_timer);
    }

    private void setupTimerInputs() {
        // Text watchers no longer needed since buttons are always active
        // Users get validation feedback when clicking Set Timer button
    }

    private void setupButtons() {
        btnUpdateApps.setOnClickListener(v -> showAppSelector());
        btnSetTimer.setOnClickListener(v -> {
            if (hasTimerBeenSet) {
                // Button should be disabled, but add extra protection
                Toast.makeText(this, "Timer already set! Clear the current timer to set a new one.", Toast.LENGTH_LONG)
                        .show();
                return;
            }
            setTimer();
        });
        btnClearTimer.setOnClickListener(v -> clearTimer());
        btnRefreshTimer.setOnClickListener(v -> refreshTimerData());
    }

    private void showAppSelector() {
        Intent intent = new Intent(this, ChildAppListActivity.class);
        intent.putExtra("deviceId", currentChildDeviceId);
        intent.putExtra("mode", "select_multiple");
        intent.putExtra("title", "Select Apps for Timer");

        // Send currently selected apps as preselected package names
        if (!selectedApps.isEmpty()) {
            ArrayList<String> selectedAppsList = new ArrayList<>(selectedApps);
            intent.putStringArrayListExtra("preselected_packages", selectedAppsList);
            Log.d(TAG, "Sending " + selectedAppsList.size() + " preselected packages: " + selectedAppsList.toString());
        }

        startActivityForResult(intent, 1001);
        Log.d(TAG, "Launched app selector for timer");
    }

    private void setTimer() {
        // Validate inputs
        String hoursStr = etTimerHours.getText().toString().trim();
        String minutesStr = etTimerMinutes.getText().toString().trim();

        if (hoursStr.isEmpty())
            hoursStr = "0";
        if (minutesStr.isEmpty())
            minutesStr = "0";

        try {
            int hours = Integer.parseInt(hoursStr);
            int minutes = Integer.parseInt(minutesStr);

            if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) {
                Toast.makeText(this, "Invalid time format", Toast.LENGTH_SHORT).show();
                return;
            }

            if (hours == 0 && minutes == 0) {
                Toast.makeText(this, "Timer duration must be greater than 0", Toast.LENGTH_SHORT).show();
                return;
            }

            if (selectedApps.isEmpty()) {
                Log.d(TAG, "selectedApps is empty when setting timer!");
                Log.d(TAG, "selectedApps size: " + selectedApps.size());
                Toast.makeText(this, "Please select at least one app", Toast.LENGTH_SHORT).show();
                return;
            }

            Log.d(TAG, "Setting timer with " + selectedApps.size() + " apps: " + selectedApps.toString());

            // Create timer data with COMPLETE information for midnight reset
            long totalTimeMs = (hours * 60 * 60 * 1000L) + (minutes * 60 * 1000L);
            String todayDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

            Map<String, Object> timerData = new HashMap<>();
            timerData.put("hours", hours);
            timerData.put("minutes", minutes);
            timerData.put("selectedApps", new ArrayList<>(selectedApps));
            timerData.put("startTime", System.currentTimeMillis());
            timerData.put("remainingTimeMs", totalTimeMs);
            timerData.put("originalTimeMs", totalTimeMs); // Original time for midnight reset reference
            timerData.put("isActive", true);
            timerData.put("lastResetDate", todayDate);
            timerData.put("createdDate", todayDate); // Track when timer was first created

            // Save to Firebase
            limiterRef.child(currentChildDeviceId).setValue(timerData)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "Timer set successfully for device: " + currentChildDeviceId);
                        String timeText = (hours > 0 ? hours + "h " : "") + (minutes > 0 ? minutes + "m" : "");

                        Toast.makeText(this, "✅ Timer activated!\n" +
                                "⏱️ Daily limit: " + timeText + "\n" +
                                "📱 Apps: " + selectedApps.size() + " apps selected\n" +
                                "� Resets daily at midnight\n" +
                                "🔒 Button locked until manually cleared",
                                Toast.LENGTH_LONG).show();

                        isTimerActive = true;
                        hasTimerBeenSet = true; // Mark that timer has been set - button will stay disabled
                        updateButtonStates();
                        updateAppsButtonText();
                        updateAssignedAppsDisplay(); // Update assigned apps display after setting timer

                        // 🔄 Automatically display the timer after setting it
                        refreshTimerData();
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Error setting timer: " + e.getMessage());
                        Toast.makeText(this, "Error setting timer: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });

        } catch (NumberFormatException e) {
            Toast.makeText(this, "Please enter valid numbers", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearTimer() {
        // Check if timer data exists (active OR expired)
        limiterRef.child(currentChildDeviceId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists() || !dataSnapshot.hasChildren()) {
                    // No timer data exists at all - truly no timer set
                    showNoTimerSetDialog();
                    return;
                }

                // Timer data exists (either active or expired) - allow clearing
                Boolean isActive = dataSnapshot.child("isActive").getValue(Boolean.class);
                if (Boolean.TRUE.equals(isActive)) {
                    Log.d(TAG, "Clearing ACTIVE timer");
                } else {
                    Log.d(TAG, "Clearing EXPIRED timer (shown in red)");
                }

                // Show confirmation dialog for ANY existing timer (active or expired)
                showClearTimerConfirmationDialog();
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Error checking timer status: " + databaseError.getMessage());
                Toast.makeText(TimerManagementActivity.this, "Error checking timer status", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showNoTimerSetDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("No Timer Set");
        builder.setMessage("There is currently no active timer for \"" + currentChildDeviceName + "\".");
        builder.setIcon(android.R.drawable.ic_dialog_info);

        builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /**
     * Show confirmation dialog for clearing the timer
     */
    private void showClearTimerConfirmationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Clear Timer");
        builder.setMessage("Are you sure you want to clear the timer for \"" + currentChildDeviceName + "\"?\n\n" +
                "This will:\n" +
                "• Stop the current timer immediately\n" +
                "• Remove all timer settings\n" +
                "• Clear selected apps and date range\n" +
                "• Reset the timer\n\n" +
                "This action cannot be undone.");
        builder.setIcon(android.R.drawable.ic_dialog_alert);

        builder.setPositiveButton("Clear Timer", (dialog, which) -> {
            Log.d(TAG, "User confirmed timer clear for device: " + currentChildDeviceName);

            // Remove from Firebase
            limiterRef.child(currentChildDeviceId).removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "Timer cleared successfully for device: " + currentChildDeviceId);
                        Toast.makeText(this,
                                "✅ Timer cleared for " + currentChildDeviceName + "\n🔓 Set Timer button reactivated",
                                Toast.LENGTH_LONG).show();

                        // Reset local data
                        selectedApps.clear();
                        etTimerHours.setText("");
                        etTimerMinutes.setText("");

                        // Reset end date to tomorrow

                        isTimerActive = false;
                        hasTimerBeenSet = false; // CRITICAL: Reset the flag - button becomes available again
                        updateButtonStates();
                        updateAppsButtonText();
                        updateAssignedAppsDisplay(); // Update assigned apps display after clearing timer
                        displayInactiveTimer();
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Error clearing timer: " + e.getMessage());
                        Toast.makeText(this, "Error clearing timer: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            Log.d(TAG, "User cancelled timer clear");
            dialog.dismiss();
        });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void loadTimerState() {
        Log.d(TAG, "🔍 Loading timer state (real-time) for device: " + currentChildDeviceId);

        // ✅ FIX: Use addValueEventListener (real-time) instead of single-read.
        // This makes the parent page instantly reflect:
        // - Timer expiry (isActive flips to false)
        // - Midnight reset (remainingTimeMs restored, isActive=true)
        // - Timer removal by parent (node disappears)
        currentLimiterDeviceRef = limiterRef.child(currentChildDeviceId);
        limiterListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                Log.d(TAG, "📊 Real-time timer data for device: " + currentChildDeviceId + ", exists: "
                        + dataSnapshot.exists());
                if (dataSnapshot.exists()) {
                    try {
                        Integer hours = dataSnapshot.child("hours").getValue(Integer.class);
                        Integer minutes = dataSnapshot.child("minutes").getValue(Integer.class);
                        Boolean isActive = dataSnapshot.child("isActive").getValue(Boolean.class);
                        Long remainingTimeMs = dataSnapshot.child("remainingTimeMs").getValue(Long.class);

                        Log.d(TAG, "📊 isActive=" + isActive + ", remainingTimeMs=" + remainingTimeMs);

                        // Load selected apps
                        DataSnapshot appsSnapshot = dataSnapshot.child("selectedApps");
                        if (appsSnapshot.exists() && appsSnapshot.getChildrenCount() > 0) {
                            selectedApps.clear();
                            for (DataSnapshot appSnapshot : appsSnapshot.getChildren()) {
                                String app = appSnapshot.getValue(String.class);
                                if (app != null)
                                    selectedApps.add(app);
                            }
                        }

                        // ✅ FIX: Determine display state correctly
                        boolean timerDataExists = dataSnapshot.hasChildren();
                        boolean timerExpired = (remainingTimeMs != null && remainingTimeMs <= 0);
                        boolean timerRunning = Boolean.TRUE.equals(isActive) && !timerExpired;

                        if (timerRunning) {
                            // Active and time remaining — show countdown
                            if (hours != null)
                                etTimerHours.setText(String.valueOf(hours));
                            if (minutes != null)
                                etTimerMinutes.setText(String.valueOf(minutes));
                            isTimerActive = true;
                            hasTimerBeenSet = true;
                            updateButtonStates();
                            updateAppsButtonText();
                            displayTimerTime(remainingTimeMs);
                            tvTimerStatus.setText("Timer active on " + currentChildDeviceName);
                            tvTimerStatus.setTextColor(ContextCompat.getColor(TimerManagementActivity.this,
                                    android.R.color.holo_green_dark));

                        } else if (timerDataExists) {
                            // ✅ FIX: Timer exists but expired (isActive=false OR remainingTimeMs=0)
                            // Previously this case caused a blank display — now shows red 00:00
                            hasTimerBeenSet = true;
                            isTimerActive = false;
                            updateButtonStates();
                            updateAppsButtonText();
                            displayExpiredTimer();

                        } else {
                            // No timer at all
                            hasTimerBeenSet = false;
                            displayInactiveTimer();
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error parsing timer state: " + e.getMessage());
                        displayInactiveTimer();
                    }
                } else {
                    // Timer node deleted (parent removed it) — show inactive
                    hasTimerBeenSet = false;
                    selectedApps.clear();
                    displayInactiveTimer();
                    Log.d(TAG, "📭 Timer removed for device: " + currentChildDeviceName);
                }

                updateAppsButtonText();
                updateAssignedAppsDisplay();
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Error in timer listener: " + databaseError.getMessage());
                displayInactiveTimer();
            }
        };

        currentLimiterDeviceRef.addValueEventListener(limiterListener);
    }

    private void displayTimerTime(long remainingTimeMs) {
        int totalSeconds = (int) (remainingTimeMs / 1000);
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        String timeText;
        if (hours > 0) {
            timeText = String.format(Locale.getDefault(), "%02d:%02d", hours, minutes);
        } else {
            timeText = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
        }

        tvActiveTimer.setText(timeText);

        // Color coding based on remaining time
        if (remainingTimeMs > 600000) { // More than 10 minutes
            tvActiveTimer.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        } else if (remainingTimeMs > 300000) { // 5-10 minutes
            tvActiveTimer.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
        } else if (remainingTimeMs > 0) { // Less than 5 minutes
            tvActiveTimer.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
        } else {
            tvActiveTimer.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            tvActiveTimer.setText("TIME UP!");
        }
    }

    private void displayInactiveTimer() {
        tvActiveTimer.setText("00:00");
        tvActiveTimer.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        tvTimerStatus.setText("No active timer");
        tvTimerStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));

        isTimerActive = false;
        // Note: Don't reset hasTimerBeenSet here - let the calling method handle it
        // This allows displayInactiveTimer to be used for both "no timer" and "expired
        // timer" cases
        updateButtonStates();
        updateAppsButtonText();
        updateAssignedAppsDisplay(); // Update the assigned apps display
    }

    /**
     * 🔴 DISPLAY EXPIRED TIMER
     * Shows timer in RED when it has expired (00:00) but still exists
     * Timer remains visible until manually removed
     */
    private void displayExpiredTimer() {
        tvActiveTimer.setText("00:00");
        tvActiveTimer.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark)); // RED for expired
        tvTimerStatus.setText("Timer expired for today");
        tvTimerStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark)); // RED status too

        isTimerActive = false; // Timer is not actively counting down
        // hasTimerBeenSet remains true - timer exists but is expired
        updateButtonStates();
        updateAppsButtonText();
        updateAssignedAppsDisplay(); // Update the assigned apps display
    }

    /**
     * 🔄 REFRESH TIMER DATA
     * Now largely redundant because loadTimerState uses a real-time listener,
     * but kept as a manual fallback / reassurance button for the parent.
     */
    private void refreshTimerData() {
        Log.d(TAG, "🔄 Manual refresh requested for: " + currentChildDeviceName);
        // The real-time listener already keeps the UI current.
        // Just read once to confirm & show a toast.
        limiterRef.child(currentChildDeviceId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists()) {
                    hasTimerBeenSet = false;
                    selectedApps.clear();
                    displayInactiveTimer();
                    Toast.makeText(TimerManagementActivity.this,
                            "ℹ️ No timer set", Toast.LENGTH_SHORT).show();
                    updateAssignedAppsDisplay();
                    return;
                }

                Boolean isActive = dataSnapshot.child("isActive").getValue(Boolean.class);
                Long remainingTimeMs = dataSnapshot.child("remainingTimeMs").getValue(Long.class);
                boolean expired = (remainingTimeMs != null && remainingTimeMs <= 0);

                if (Boolean.TRUE.equals(isActive) && !expired) {
                    displayTimerTime(remainingTimeMs);
                    tvTimerStatus.setText("Timer active on " + currentChildDeviceName);
                    tvTimerStatus.setTextColor(ContextCompat.getColor(
                            TimerManagementActivity.this, android.R.color.holo_green_dark));
                    Toast.makeText(TimerManagementActivity.this,
                            "✅ Timer refreshed", Toast.LENGTH_SHORT).show();
                } else if (dataSnapshot.hasChildren()) {
                    hasTimerBeenSet = true;
                    isTimerActive = false;
                    updateButtonStates();
                    displayExpiredTimer();
                    Toast.makeText(TimerManagementActivity.this,
                            "⏰ Timer expired for today", Toast.LENGTH_SHORT).show();
                } else {
                    displayInactiveTimer();
                    Toast.makeText(TimerManagementActivity.this,
                            "ℹ️ No active timer found", Toast.LENGTH_SHORT).show();
                }
                updateAssignedAppsDisplay();
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "❌ Error refreshing timer data: " + databaseError.getMessage());
                Toast.makeText(TimerManagementActivity.this,
                        "❌ Error refreshing timer", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 📱 UPDATE ASSIGNED APPS DISPLAY
     * Show which apps have the usage limiter assigned to them
     */
    private void updateAssignedAppsDisplay() {
        if (tvAssignedApps == null) {
            Log.w(TAG, "⚠️ tvAssignedApps is null, cannot update display");
            return;
        }

        Log.d(TAG, "🔍 updateAssignedAppsDisplay - isTimerActive: " + isTimerActive + ", selectedApps.size(): "
                + selectedApps.size() + ", hasTimerBeenSet: " + hasTimerBeenSet);
        Log.d(TAG, "🔍 selectedApps content: " + selectedApps.toString());

        // Show apps if we have selected apps AND (timer is active OR timer has been
        // set)
        if (selectedApps.isEmpty() || (!isTimerActive && !hasTimerBeenSet)) {
            tvAssignedApps.setText("No Timer Set");
            tvAssignedApps.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
            Log.d(TAG, "📱 Showing 'No Timer Set' - Apps empty or timer not active");
        } else {
            // Show app names (get readable names from package manager)
            StringBuilder appsText = new StringBuilder();
            int count = 0;
            for (String packageName : selectedApps) {
                if (count > 0)
                    appsText.append("\n");

                // Try to get readable app name
                String appName = getAppNameFromPackage(packageName);
                appsText.append("• ").append(appName);
                count++;
            }

            tvAssignedApps.setText(appsText.toString());
            tvAssignedApps.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark));
            Log.d(TAG, "📱 Showing apps list: " + appsText.toString());
        }

        Log.d(TAG, "📱 Updated assigned apps display - Final text: " + tvAssignedApps.getText().toString());
    }

    /**
     * Get readable app name from package name
     */
    private String getAppNameFromPackage(String packageName) {
        Log.d(TAG, "🔍 Getting app name for package: " + packageName);

        // First check if we have the app name stored from app selection
        if (appNameMap.containsKey(packageName)) {
            String storedName = appNameMap.get(packageName);
            Log.d(TAG, "✅ Found stored app name: " + storedName + " for package: " + packageName);
            return storedName;
        }

        // Fallback to PackageManager if not in our stored map
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo applicationInfo = pm.getApplicationInfo(packageName, 0);
            String appName = (String) pm.getApplicationLabel(applicationInfo);
            Log.d(TAG, "✅ Found app name via PackageManager: " + appName + " for package: " + packageName);

            // Return app name if it's different from package name, otherwise try
            // alternative
            if (appName != null && !appName.equals(packageName)) {
                return appName;
            } else {
                // Try to get a more readable name by processing the package name
                return getReadableNameFromPackage(packageName);
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            Log.w(TAG, "❌ Could not find app name for package: " + packageName + ", error: " + e.getMessage());
            // Return a more readable version of the package name
            return getReadableNameFromPackage(packageName);
        } catch (Exception e) {
            Log.e(TAG,
                    "❌ Unexpected error getting app name for package: " + packageName + ", error: " + e.getMessage());
            return getReadableNameFromPackage(packageName);
        }
    }

    /**
     * Convert package name to a more readable format as fallback
     */
    private String getReadableNameFromPackage(String packageName) {
        try {
            // Split by dots and take the last part, then capitalize
            String[] parts = packageName.split("\\.");
            if (parts.length > 0) {
                String lastPart = parts[parts.length - 1];
                // Capitalize first letter and return
                if (lastPart.length() > 0) {
                    String readable = lastPart.substring(0, 1).toUpperCase() + lastPart.substring(1);
                    Log.d(TAG, "📝 Created readable name: " + readable + " from package: " + packageName);
                    return readable;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error creating readable name from package: " + packageName);
        }
        // Final fallback
        return packageName;
    }

    private void startTimerMonitoring() {
        // Simplified timer monitoring - removed real-time updates
        // Timer updates are now manual via refresh button for easier data processing
        Log.d(TAG, "Timer monitoring simplified - using manual refresh instead of real-time updates");

        // Initial update of assigned apps display
        updateAssignedAppsDisplay();
    }

    private void updateButtonStates() {
        Log.d(TAG, "🔧 updateButtonStates - hasTimerBeenSet: " + hasTimerBeenSet + ", isTimerActive: " + isTimerActive);

        // Set Timer button: Once a timer is set, it stays disabled until manually
        // cleared
        // This prevents disruption of future automatic timers
        btnSetTimer.setEnabled(!hasTimerBeenSet);

        // See App List button: Disable when timer is set to prevent app changes
        btnUpdateApps.setEnabled(!hasTimerBeenSet);

        // Clear Timer button: Always enabled (shows popup if no timer)
        btnClearTimer.setEnabled(true);

        Log.d(TAG, "🔧 Button states - SetTimer enabled: " + btnSetTimer.isEnabled() + ", SeeAppList enabled: "
                + btnUpdateApps.isEnabled());

        // Update button text to reflect state
        if (hasTimerBeenSet) {
            btnSetTimer.setText("Timer Set (Clear to Reset)");
        } else {
            btnSetTimer.setText("Set Timer");
        }
    }

    private void updateAppsButtonText() {
        String buttonText = "See App List";
        Log.d(TAG, "updateAppsButtonText - selectedApps.isEmpty(): " + selectedApps.isEmpty());
        Log.d(TAG, "updateAppsButtonText - selectedApps.size(): " + selectedApps.size());
        Log.d(TAG, "updateAppsButtonText - selectedApps: " + selectedApps.toString());

        // Always show "See App List" regardless of timer state
        btnUpdateApps.setText(buttonText);
        Log.d(TAG, "updateAppsButtonText - button text set to: " + buttonText);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            // Handle app selection result
            ArrayList<AppInfo> selectedAppInfos = data.getParcelableArrayListExtra("selected_apps");
            Log.d(TAG, "onActivityResult - requestCode: " + requestCode + ", resultCode: " + resultCode);
            Log.d(TAG, "onActivityResult - data is null: " + (data == null));
            Log.d(TAG, "onActivityResult - selectedAppInfos is null: " + (selectedAppInfos == null));

            if (selectedAppInfos != null) {
                Log.d(TAG, "Received " + selectedAppInfos.size() + " selected AppInfo objects for timer");

                // Extract package names and store app names for display
                ArrayList<String> selectedAppPackages = new ArrayList<>();
                appNameMap.clear(); // Clear previous app names

                for (AppInfo appInfo : selectedAppInfos) {
                    if (appInfo != null && appInfo.packageName != null) {
                        selectedAppPackages.add(appInfo.packageName);

                        // Store the app name for display purposes
                        String displayName = appInfo.name != null ? appInfo.name : appInfo.packageName;
                        appNameMap.put(appInfo.packageName, displayName);

                        Log.d(TAG, "📱 Mapped app: " + appInfo.packageName + " → " + displayName);
                    }
                }

                Log.d(TAG, "Extracted " + selectedAppPackages.size() + " package names: "
                        + selectedAppPackages.toString());
                Log.d(TAG, "Stored " + appNameMap.size() + " app name mappings");

                selectedApps.clear();
                selectedApps.addAll(selectedAppPackages);

                Log.d(TAG, "selectedApps after update: " + selectedApps.toString());

                updateAppsButtonText();
                updateButtonStates();

                Toast.makeText(this, "Selected " + selectedApps.size() + " apps for timer", Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG, "selectedAppInfos was null in onActivityResult");
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent); // Update the intent

        // 🔧 DEVICE-SPECIFIC STATE ISOLATION: Handle device switch when activity is
        // reused
        String newDeviceId = intent.getStringExtra("deviceId");
        String newDeviceName = intent.getStringExtra("deviceName");

        if (newDeviceId != null && !newDeviceId.equals(currentChildDeviceId)) {
            Log.d(TAG, "🔄 Device switch detected in onNewIntent: " + currentChildDeviceId + " → " + newDeviceId);

            // Update device info
            currentChildDeviceId = newDeviceId;
            currentChildDeviceName = newDeviceName;

            // Clear all previous device state
            clearAllDeviceState();

            // Reinitialize for new device
            Log.d(TAG, "🔄 Reinitializing for new device: " + currentChildDeviceName);
            loadTimerState();
            // Removed real-time timer monitoring - using refresh button instead
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Stop timer monitoring
        if (timerHandler != null && timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }

        // Detach Firebase listeners
        if (currentLimiterDeviceRef != null && limiterListener != null) {
            currentLimiterDeviceRef.removeEventListener(limiterListener);
        }

        Log.d(TAG, "TimerManagementActivity destroyed");
    }
}
