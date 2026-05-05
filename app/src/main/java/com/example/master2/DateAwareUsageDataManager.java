package com.example.master2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.example.master2.models.AppUsage;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 📅 DATE-AWARE USAGE DATA MANAGER
 * 
 * Fixes the daily data separation issue where old day data was being carried over to new days.
 * 
 * KEY FIXES:
 * ✅ Stores each day's data with specific date keys (yyyy-MM-dd)
 * ✅ At midnight (00:00), creates NEW empty data for the new day
 * ✅ Preserves old day data in Firebase under that day's date
 * ✅ Parent app reads from correct date-specific Firebase paths
 * ✅ No more data contamination between days
 * 
 * FIREBASE STRUCTURE:
 * usage_data/
 *   {deviceId}/
 *     daily_data/
 *       2024-01-15/  <- Old day data stays here
 *         totalMinutes: 240
 *         apps: [list of apps with usage]
 *       2024-01-16/  <- Today's data starts fresh
 *         totalMinutes: 0 (starts fresh)
 *         apps: [] (starts empty)
 */
public class DateAwareUsageDataManager {
    private static final String TAG = "DateAwareUsageManager";
    
    private final Context context;
    private final String deviceId;
    private final DatabaseReference firebaseRef;
    private final SharedPreferences prefs;
    
    // Date format for Firebase keys
    private final SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    
    // Preferences keys
    private static final String PREF_NAME = "date_aware_usage";
    private static final String KEY_LAST_MIDNIGHT_RESET = "last_midnight_reset";
    private static final String KEY_CURRENT_DAY_DATA_INITIALIZED = "current_day_initialized";
    
    public DateAwareUsageDataManager(Context context, String deviceId) {
        this.context = context;
        this.deviceId = deviceId;
        this.firebaseRef = FirebaseDatabase.getInstance()
            .getReference("usage_data")
            .child(deviceId)
            .child("daily_data");
        this.prefs = context.getSharedPreferences(PREF_NAME + "_" + deviceId, Context.MODE_PRIVATE);
        
        Log.d(TAG, "🏗️ DateAwareUsageDataManager initialized for device: " + deviceId);
        
        // Check if we need to initialize today's data
        ensureTodayDataExists();
    }
    
    /**
     * 🌅 Ensure today's data structure exists (starts fresh if new day)
     */
    private void ensureTodayDataExists() {
        String todayKey = getTodayDateKey();
        String lastInitializedDay = prefs.getString(KEY_CURRENT_DAY_DATA_INITIALIZED, "");
        
        if (!todayKey.equals(lastInitializedDay)) {
            Log.d(TAG, "🌅 NEW DAY DETECTED! Creating fresh data structure for: " + todayKey);
            Log.d(TAG, "   Previous day was: " + lastInitializedDay);
            
            // Create fresh data structure for today
            createFreshDayData(todayKey);
            
            // Update the last initialized day
            prefs.edit()
                .putString(KEY_CURRENT_DAY_DATA_INITIALIZED, todayKey)
                .putLong(KEY_LAST_MIDNIGHT_RESET, System.currentTimeMillis())
                .apply();
            
            Log.d(TAG, "✅ Fresh day data created for: " + todayKey);
        } else {
            Log.d(TAG, "📅 Same day (" + todayKey + ") - using existing data structure");
        }
    }
    
    /**
     * 🆕 Create fresh, empty data structure for a new day
     */
    private void createFreshDayData(String dateKey) {
        Map<String, Object> freshDayData = new HashMap<>();
        freshDayData.put("date", dateKey);
        freshDayData.put("created_at", System.currentTimeMillis());
        freshDayData.put("totalMinutes", 0.0f);
        freshDayData.put("totalTimeText", "0m");
        freshDayData.put("appUsageList", new ArrayList<>());
        freshDayData.put("dayLabel", getDayLabelForDate(dateKey));
        freshDayData.put("data_status", "fresh_day_started");
        
        // Store in Firebase under today's date
        firebaseRef.child(dateKey).setValue(freshDayData)
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "✅ Fresh day data created in Firebase for: " + dateKey);
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "❌ Failed to create fresh day data: " + e.getMessage());
            });
    }
    
    /**
     * 📊 Update usage data for today (incremental updates)
     */
    public void updateTodayUsageData(List<AppUsage> appUsageList, float totalMinutes, String totalTimeText) {
        String todayKey = getTodayDateKey();
        
        Log.d(TAG, "📊 Updating usage data for TODAY (" + todayKey + "): " + totalTimeText);
        
        Map<String, Object> todayUpdates = new HashMap<>();
        todayUpdates.put("totalMinutes", totalMinutes);
        todayUpdates.put("totalTimeText", totalTimeText);
        todayUpdates.put("appUsageList", appUsageList);
        todayUpdates.put("last_updated", System.currentTimeMillis());
        todayUpdates.put("data_status", "active_tracking");
        
        // Update only today's data in Firebase
        firebaseRef.child(todayKey).updateChildren(todayUpdates)
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "✅ TODAY's data updated: " + totalTimeText + " across " + appUsageList.size() + " apps");
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "❌ Failed to update today's data: " + e.getMessage());
            });
    }
    
    /**
     * 📖 Read usage data for a specific date (for parent app display)
     */
    public void getUsageDataForDate(String dateKey, UsageDataCallback callback) {
        Log.d(TAG, "📖 Reading usage data for date: " + dateKey);
        
        firebaseRef.child(dateKey).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    try {
                        Float totalMinutes = snapshot.child("totalMinutes").getValue(Float.class);
                        String totalTimeText = snapshot.child("totalTimeText").getValue(String.class);
                        String dayLabel = snapshot.child("dayLabel").getValue(String.class);
                        
                        // Extract app usage list
                        List<AppUsage> appUsageList = new ArrayList<>();
                        DataSnapshot appListSnapshot = snapshot.child("appUsageList");
                        if (appListSnapshot.exists()) {
                            for (DataSnapshot appSnapshot : appListSnapshot.getChildren()) {
                                AppUsage app = appSnapshot.getValue(AppUsage.class);
                                if (app != null) {
                                    appUsageList.add(app);
                                }
                            }
                        }
                        
                        // Create usage data object
                        UsageDataForDate usageData = new UsageDataForDate(
                            dateKey,
                            totalMinutes != null ? totalMinutes : 0f,
                            totalTimeText != null ? totalTimeText : "0m",
                            dayLabel != null ? dayLabel : dateKey,
                            appUsageList
                        );
                        
                        Log.d(TAG, "✅ Found data for " + dateKey + ": " + totalTimeText + " (" + appUsageList.size() + " apps)");
                        callback.onDataReceived(usageData);
                        
                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error parsing data for " + dateKey + ": " + e.getMessage());
                        callback.onError("Error parsing usage data");
                    }
                } else {
                    Log.d(TAG, "📭 No data found for " + dateKey + " - returning empty data");
                    
                    // Return empty data for this date
                    UsageDataForDate emptyData = new UsageDataForDate(
                        dateKey, 0f, "0m", getDayLabelForDate(dateKey), new ArrayList<>()
                    );
                    callback.onDataReceived(emptyData);
                }
            }
            
            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "❌ Firebase error reading data for " + dateKey + ": " + error.getMessage());
                callback.onError("Database error: " + error.getMessage());
            }
        });
    }
    
    /**
     * 📅 Get usage data for today specifically
     */
    public void getTodayUsageData(UsageDataCallback callback) {
        String todayKey = getTodayDateKey();
        Log.d(TAG, "📅 Getting TODAY's usage data (" + todayKey + ")");
        getUsageDataForDate(todayKey, callback);
    }
    
    /**
     * 📊 Get usage data for the last 7 days (for charts and summaries)
     */
    public void getLast7DaysUsageData(MultiDayUsageCallback callback) {
        List<String> last7Days = getLast7DayKeys();
        List<UsageDataForDate> results = new ArrayList<>();
        final int[] pendingRequests = {last7Days.size()};
        
        Log.d(TAG, "📊 Getting last 7 days usage data: " + last7Days);
        
        for (String dayKey : last7Days) {
            getUsageDataForDate(dayKey, new UsageDataCallback() {
                @Override
                public void onDataReceived(UsageDataForDate data) {
                    results.add(data);
                    pendingRequests[0]--;
                    
                    if (pendingRequests[0] == 0) {
                        // Sort results by date to ensure correct order
                        results.sort((a, b) -> a.dateKey.compareTo(b.dateKey));
                        callback.onDataReceived(results);
                    }
                }
                
                @Override
                public void onError(String error) {
                    // Add empty data for failed requests
                    results.add(new UsageDataForDate(dayKey, 0f, "0m", getDayLabelForDate(dayKey), new ArrayList<>()));
                    pendingRequests[0]--;
                    
                    if (pendingRequests[0] == 0) {
                        results.sort((a, b) -> a.dateKey.compareTo(b.dateKey));
                        callback.onDataReceived(results);
                    }
                }
            });
        }
    }
    
    /**
     * 🗑️ Clear all usage data (for testing or reset)
     */
    public void clearAllData() {
        Log.d(TAG, "🗑️ Clearing all usage data for device: " + deviceId);
        
        firebaseRef.removeValue()
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "✅ All usage data cleared");
                prefs.edit().clear().apply();
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "❌ Failed to clear data: " + e.getMessage());
            });
    }
    
    /**
     * 🕛 Manually trigger midnight reset (for testing)
     */
    public void triggerMidnightReset() {
        Log.d(TAG, "🕛 Manually triggering midnight reset");
        
        // Clear the last initialized day to force new day creation
        prefs.edit().remove(KEY_CURRENT_DAY_DATA_INITIALIZED).apply();
        
        // Re-initialize today's data
        ensureTodayDataExists();
    }
    
    // Helper methods
    
    private String getTodayDateKey() {
        return dateFormatter.format(new Date());
    }
    
    private List<String> getLast7DayKeys() {
        List<String> dayKeys = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        
        // Generate last 7 days including today
        for (int i = 6; i >= 0; i--) {
            Calendar day = (Calendar) cal.clone();
            day.add(Calendar.DAY_OF_YEAR, -i);
            dayKeys.add(dateFormatter.format(day.getTime()));
        }
        
        return dayKeys;
    }
    
    private String getDayLabelForDate(String dateKey) {
        try {
            Date date = dateFormatter.parse(dateKey);
            Calendar cal = Calendar.getInstance();
            cal.setTime(date);
            
            Calendar today = Calendar.getInstance();
            Calendar yesterday = Calendar.getInstance();
            yesterday.add(Calendar.DAY_OF_YEAR, -1);
            
            if (isSameDay(cal, today)) {
                return "Today";
            } else if (isSameDay(cal, yesterday)) {
                return "Yesterday";
            } else {
                SimpleDateFormat dayFormat = new SimpleDateFormat("MMM dd", Locale.getDefault());
                return dayFormat.format(date);
            }
        } catch (Exception e) {
            return dateKey;
        }
    }
    
    private boolean isSameDay(Calendar cal1, Calendar cal2) {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }
    
    // Data classes and interfaces
    
    public static class UsageDataForDate {
        public final String dateKey;
        public final float totalMinutes;
        public final String totalTimeText;
        public final String dayLabel;
        public final List<AppUsage> appUsageList;
        
        public UsageDataForDate(String dateKey, float totalMinutes, String totalTimeText, 
                               String dayLabel, List<AppUsage> appUsageList) {
            this.dateKey = dateKey;
            this.totalMinutes = totalMinutes;
            this.totalTimeText = totalTimeText;
            this.dayLabel = dayLabel;
            this.appUsageList = appUsageList;
        }
    }
    
    public interface UsageDataCallback {
        void onDataReceived(UsageDataForDate data);
        void onError(String error);
    }
    
    public interface MultiDayUsageCallback {
        void onDataReceived(List<UsageDataForDate> data);
    }
}