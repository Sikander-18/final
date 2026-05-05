package com.example.master2;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.master2.models.DailyUsageLimit;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

public class DailyLimitsActivity extends AppCompatActivity {
    private static final String TAG = "DailyLimitsActivity";
    
    private String deviceId;
    private String deviceName;
    private DatabaseReference limitsRef;
    private DailyUsageLimit currentLimits;
    
    // UI Components
    private LinearLayout llAppLimits;
    private LinearLayout llCategoryLimits;
    private EditText etTotalScreenTime;
    private Button btnSaveLimits;
    private Button btnClearLimits;
    private TextView tvCurrentUsage;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_daily_limits);
        
        // Get device info from intent
        deviceId = getIntent().getStringExtra("deviceId");
        deviceName = getIntent().getStringExtra("deviceName");
        
        if (deviceId == null || deviceName == null) {
            Toast.makeText(this, "Device information not provided", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        // Initialize Firebase reference
        limitsRef = FirebaseDatabase.getInstance().getReference("daily_usage_limits").child(deviceId);
        
        // Initialize UI components
        initializeViews();
        
        // Load current limits
        loadCurrentLimits();
        
        Log.d(TAG, "DailyLimitsActivity created for device: " + deviceName + " (" + deviceId + ")");
    }
    
    private void initializeViews() {
        llAppLimits = findViewById(R.id.llAppLimits);
        llCategoryLimits = findViewById(R.id.llCategoryLimits);
        etTotalScreenTime = findViewById(R.id.etTotalScreenTime);
        btnSaveLimits = findViewById(R.id.btnSaveLimits);
        btnClearLimits = findViewById(R.id.btnClearLimits);
        tvCurrentUsage = findViewById(R.id.tvCurrentUsage);
        
        // Set up button listeners
        btnSaveLimits.setOnClickListener(v -> saveLimits());
        btnClearLimits.setOnClickListener(v -> clearLimits());
        
        // Set up app limits section
        setupAppLimitsSection();
        
        // Set up category limits section
        setupCategoryLimitsSection();
    }
    
    private void setupAppLimitsSection() {
        // Add common apps with limit inputs
        String[] commonApps = {
            "Instagram", "com.instagram.android",
            "Snapchat", "com.snapchat.android",
            "TikTok", "com.tiktok.android",
            "Facebook", "com.facebook.katana",
            "WhatsApp", "com.whatsapp",
            "YouTube", "com.youtube.android",
            "Netflix", "com.netflix.mediaclient",
            "Spotify", "com.spotify.music"
        };
        
        for (int i = 0; i < commonApps.length; i += 2) {
            String appName = commonApps[i];
            String packageName = commonApps[i + 1];
            
            addAppLimitRow(appName, packageName);
        }
    }
    
    private void setupCategoryLimitsSection() {
        // Add category limits
        String[] categories = {"Social", "Games", "Entertainment", "Others"};
        
        for (String category : categories) {
            addCategoryLimitRow(category);
        }
    }
    
    private void addAppLimitRow(String appName, String packageName) {
        View row = getLayoutInflater().inflate(R.layout.item_app_limit, llAppLimits, false);
        
        TextView tvAppName = row.findViewById(R.id.tvAppName);
        EditText etAppLimit = row.findViewById(R.id.etAppLimit);
        
        tvAppName.setText(appName);
        etAppLimit.setTag(packageName); // Store package name as tag
        
        llAppLimits.addView(row);
    }
    
    private void addCategoryLimitRow(String category) {
        View row = getLayoutInflater().inflate(R.layout.item_category_limit, llCategoryLimits, false);
        
        TextView tvCategoryName = row.findViewById(R.id.tvCategoryName);
        EditText etCategoryLimit = row.findViewById(R.id.etCategoryLimit);
        
        tvCategoryName.setText(category);
        etCategoryLimit.setTag(category); // Store category as tag
        
        llCategoryLimits.addView(row);
    }
    
    private void loadCurrentLimits() {
        limitsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    currentLimits = snapshot.getValue(DailyUsageLimit.class);
                    if (currentLimits != null) {
                        updateUIWithLimits();
                        updateCurrentUsageDisplay();
                    }
                } else {
                    // Create new limits object
                    currentLimits = new DailyUsageLimit(deviceId, deviceName);
                    limitsRef.setValue(currentLimits);
                }
            }
            
            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Failed to load limits: " + error.getMessage());
                Toast.makeText(DailyLimitsActivity.this, "Failed to load limits", Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    private void updateUIWithLimits() {
        if (currentLimits == null) return;
        
        // Update total screen time limit
        if (currentLimits.totalScreenTimeLimit > 0) {
            long hours = currentLimits.totalScreenTimeLimit / (60 * 60 * 1000);
            long minutes = (currentLimits.totalScreenTimeLimit % (60 * 60 * 1000)) / (60 * 1000);
            etTotalScreenTime.setText(hours + ":" + String.format("%02d", minutes));
        }
        
        // Update app limits
        for (int i = 0; i < llAppLimits.getChildCount(); i++) {
            View row = llAppLimits.getChildAt(i);
            EditText etLimit = row.findViewById(R.id.etAppLimit);
            String packageName = (String) etLimit.getTag();
            
            Long limit = currentLimits.appLimits.get(packageName);
            if (limit != null && limit > 0) {
                long hours = limit / (60 * 60 * 1000);
                long minutes = (limit % (60 * 60 * 1000)) / (60 * 1000);
                etLimit.setText(hours + ":" + String.format("%02d", minutes));
            }
        }
        
        // Update category limits
        for (int i = 0; i < llCategoryLimits.getChildCount(); i++) {
            View row = llCategoryLimits.getChildAt(i);
            EditText etLimit = row.findViewById(R.id.etCategoryLimit);
            String category = (String) etLimit.getTag();
            
            Long limit = currentLimits.categoryLimits.get(category);
            if (limit != null && limit > 0) {
                long hours = limit / (60 * 60 * 1000);
                long minutes = (limit % (60 * 60 * 1000)) / (60 * 1000);
                etLimit.setText(hours + ":" + String.format("%02d", minutes));
            }
        }
    }
    
    private void updateCurrentUsageDisplay() {
        if (currentLimits == null) return;
        
        StringBuilder usageText = new StringBuilder("Today's Usage:\n");
        
        // Show current app usage
        for (Map.Entry<String, Long> entry : currentLimits.currentAppUsage.entrySet()) {
            String packageName = entry.getKey();
            long usage = entry.getValue();
            String appName = getAppName(packageName);
            usageText.append(appName).append(": ").append(currentLimits.formatTime(usage)).append("\n");
        }
        
        // Show current category usage
        for (Map.Entry<String, Long> entry : currentLimits.currentCategoryUsage.entrySet()) {
            String category = entry.getKey();
            long usage = entry.getValue();
            usageText.append(category).append(": ").append(currentLimits.formatTime(usage)).append("\n");
        }
        
        // Show total usage
        usageText.append("Total: ").append(currentLimits.formatTime(currentLimits.currentTotalUsage));
        
        tvCurrentUsage.setText(usageText.toString());
    }
    
    private void saveLimits() {
        if (currentLimits == null) {
            Toast.makeText(this, "Error: Limits not loaded", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            // Parse total screen time limit
            String totalTimeStr = etTotalScreenTime.getText().toString().trim();
            if (!totalTimeStr.isEmpty()) {
                String[] parts = totalTimeStr.split(":");
                if (parts.length == 2) {
                    int hours = Integer.parseInt(parts[0]);
                    int minutes = Integer.parseInt(parts[1]);
                    currentLimits.totalScreenTimeLimit = (hours * 60 * 60 * 1000L) + (minutes * 60 * 1000L);
                }
            }
            
            // Parse app limits
            currentLimits.appLimits.clear();
            for (int i = 0; i < llAppLimits.getChildCount(); i++) {
                View row = llAppLimits.getChildAt(i);
                EditText etLimit = row.findViewById(R.id.etAppLimit);
                String packageName = (String) etLimit.getTag();
                String limitStr = etLimit.getText().toString().trim();
                
                if (!limitStr.isEmpty()) {
                    String[] parts = limitStr.split(":");
                    if (parts.length == 2) {
                        int hours = Integer.parseInt(parts[0]);
                        int minutes = Integer.parseInt(parts[1]);
                        long limitMs = (hours * 60 * 60 * 1000L) + (minutes * 60 * 1000L);
                        currentLimits.appLimits.put(packageName, limitMs);
                    }
                }
            }
            
            // Parse category limits
            currentLimits.categoryLimits.clear();
            for (int i = 0; i < llCategoryLimits.getChildCount(); i++) {
                View row = llCategoryLimits.getChildAt(i);
                EditText etLimit = row.findViewById(R.id.etCategoryLimit);
                String category = (String) etLimit.getTag();
                String limitStr = etLimit.getText().toString().trim();
                
                if (!limitStr.isEmpty()) {
                    String[] parts = limitStr.split(":");
                    if (parts.length == 2) {
                        int hours = Integer.parseInt(parts[0]);
                        int minutes = Integer.parseInt(parts[1]);
                        long limitMs = (hours * 60 * 60 * 1000L) + (minutes * 60 * 1000L);
                        currentLimits.categoryLimits.put(category, limitMs);
                    }
                }
            }
            
            // Save to Firebase
            limitsRef.setValue(currentLimits)
                    .addOnSuccessListener(aVoid -> {
                        Toast.makeText(this, "Daily limits saved successfully", Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "Daily limits saved for device: " + deviceName);
                    })
                    .addOnFailureListener(e -> {
                        Toast.makeText(this, "Failed to save limits: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "Failed to save limits: " + e.getMessage());
                    });
                    
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Please enter valid time format (HH:MM)", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void clearLimits() {
        new AlertDialog.Builder(this)
                .setTitle("Clear All Limits")
                .setMessage("Are you sure you want to clear all daily usage limits for " + deviceName + "?")
                .setPositiveButton("Clear", (dialog, which) -> {
                    if (currentLimits != null) {
                        currentLimits.appLimits.clear();
                        currentLimits.categoryLimits.clear();
                        currentLimits.totalScreenTimeLimit = 0;
                        
                        limitsRef.setValue(currentLimits)
                                .addOnSuccessListener(aVoid -> {
                                    Toast.makeText(this, "All limits cleared", Toast.LENGTH_SHORT).show();
                                    updateUIWithLimits();
                                })
                                .addOnFailureListener(e -> {
                                    Toast.makeText(this, "Failed to clear limits: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
    
    private String getAppName(String packageName) {
        try {
            return getPackageManager().getApplicationLabel(
                getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return packageName;
        }
    }
} 