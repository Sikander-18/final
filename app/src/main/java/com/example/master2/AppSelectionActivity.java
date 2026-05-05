package com.example.master2;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppSelectionActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "selected_apps";
    private static final String KEY_SELECTED_APPS = "apps";
    
    // Device information for device-specific data isolation
    private String deviceId;
    private String deviceName;
    
    // Popular apps that should appear at the top
    private static final List<String> POPULAR_PACKAGES = Arrays.asList(
            "com.google.android.youtube",     // YouTube
            "com.instagram.android",          // Instagram
            "com.snapchat.android",           // Snapchat
            "com.tencent.ig",                 // BGMI
            "com.dts.freefireth",             // Free Fire
            "com.facebook.katana",            // Facebook
            "com.twitter.android",            // Twitter
            "com.whatsapp",                   // WhatsApp
            "com.tiktok.android",             // TikTok
            "com.netflix.mediaclient"         // Netflix
    );
    
    private Set<String> selectedPackages = new HashSet<>();
    private List<AppInfo> allApps = new ArrayList<>();
    private AppListAdapter adapter;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_selection);
        
        // Get device information from intent for device-specific data
        deviceId = getIntent().getStringExtra("deviceId");
        deviceName = getIntent().getStringExtra("deviceName");
        
        if (deviceId == null) {
            Toast.makeText(this, "Error: No device selected", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        setTitle("Select Apps to Monitor - " + (deviceName != null ? deviceName : "Device"));
        
        loadSelectedApps();
        loadAllApps();
        setupRecyclerView();
        setupSaveButton();
    }
    
    private void loadSelectedApps() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // Use device-specific key for app selections
        String deviceSpecificKey = KEY_SELECTED_APPS + "_" + deviceId;
        selectedPackages = new HashSet<>(prefs.getStringSet(deviceSpecificKey, new HashSet<>()));
        
        Log.d("AppSelectionActivity", "Loaded " + selectedPackages.size() + 
                " selected apps for device: " + deviceName + " (" + deviceId + ")");
    }
    
    private void loadAllApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        
        List<AppInfo> popularApps = new ArrayList<>();
        List<AppInfo> otherApps = new ArrayList<>();
        
        for (ApplicationInfo appInfo : apps) {
            // Include ALL apps - both with and without launch intents
            // This includes system apps, background services, and all user apps
            String packageName = appInfo.packageName;
            
            // Skip our own app only
            if (packageName.equals(getPackageName()) || 
                packageName.contains("com.example.master2")) {
                continue;
            }
            
            try {
                String appName = appInfo.loadLabel(pm).toString();
                Drawable appIcon = appInfo.loadIcon(pm);
                
                AppInfo app = new AppInfo(packageName, appName, appIcon);
                
                if (POPULAR_PACKAGES.contains(packageName)) {
                    popularApps.add(app);
                } else {
                    otherApps.add(app);
                }
            } catch (Exception e) {
                // Skip apps that can't be processed (corrupted or inaccessible)
                continue;
            }
        }
        
        // Sort popular apps by name
        Collections.sort(popularApps, Comparator.comparing(a -> a.appName));
        // Sort other apps by name
        Collections.sort(otherApps, Comparator.comparing(a -> a.appName));
        
        // Combine lists with popular apps first
        allApps.clear();
        allApps.addAll(popularApps);
        allApps.addAll(otherApps);
    }

    private void setupRecyclerView() {
        RecyclerView recyclerView = findViewById(R.id.recyclerViewApps);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppListAdapter(allApps, selectedPackages);
        recyclerView.setAdapter(adapter);
    }
    
    private void setupSaveButton() {
        Button btnSave = findViewById(R.id.btnSaveSelection);
        btnSave.setOnClickListener(v -> saveSelectedApps());
    }
    
    private void saveSelectedApps() {
        Set<String> currentSelection = adapter.getSelectedPackages();
        
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // Use device-specific key for saving app selections
        String deviceSpecificKey = KEY_SELECTED_APPS + "_" + deviceId;
        prefs.edit().putStringSet(deviceSpecificKey, currentSelection).apply();
        
        Toast.makeText(this, "Selection saved: " + currentSelection.size() + " apps for " + deviceName, 
                Toast.LENGTH_SHORT).show();
        
        Log.d("AppSelectionActivity", "Saved " + currentSelection.size() + 
                " apps for device: " + deviceName + " (" + deviceId + ")");
        finish();
    }
    
    // AppInfo class to hold app data
    private static class AppInfo {
        String packageName;
        String appName;
        Drawable appIcon;
        
        AppInfo(String packageName, String appName, Drawable appIcon) {
            this.packageName = packageName;
            this.appName = appName;
            this.appIcon = appIcon;
        }
    }
    
    // RecyclerView Adapter
    private static class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.AppViewHolder> {
        private final List<AppInfo> appList;
        private final Set<String> selectedPackages;
        
        AppListAdapter(List<AppInfo> appList, Set<String> selectedPackages) {
            this.appList = appList;
            this.selectedPackages = new HashSet<>(selectedPackages);
        }
        
        @NonNull
        @Override
        public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app, parent, false);
            return new AppViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
            AppInfo app = appList.get(position);
            
            holder.tvAppName.setText(app.appName);
            holder.imgAppIcon.setImageDrawable(app.appIcon);
            holder.checkboxApp.setChecked(selectedPackages.contains(app.packageName));
            
            // Handle checkbox changes
            holder.checkboxApp.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    selectedPackages.add(app.packageName);
                } else {
                    selectedPackages.remove(app.packageName);
                }
            });
            
            // Handle row clicks
            holder.itemView.setOnClickListener(v -> {
                boolean newState = !holder.checkboxApp.isChecked();
                holder.checkboxApp.setChecked(newState);
            });
        }
        
        @Override
        public int getItemCount() {
            return appList.size();
        }
        
        public Set<String> getSelectedPackages() {
            return new HashSet<>(selectedPackages);
        }
        
        // ViewHolder class
        static class AppViewHolder extends RecyclerView.ViewHolder {
            ImageView imgAppIcon;
            TextView tvAppName;
            CheckBox checkboxApp;
            
            AppViewHolder(@NonNull View itemView) {
                super(itemView);
                imgAppIcon = itemView.findViewById(R.id.imgAppIcon);
                tvAppName = itemView.findViewById(R.id.tvAppName);
                checkboxApp = itemView.findViewById(R.id.checkboxApp);
            }
        }
    }
} 