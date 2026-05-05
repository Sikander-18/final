package com.example.susage.models;

import android.graphics.drawable.Drawable;

/**
 * Model class representing usage information for a single app.
 */
public class AppUsageInfo {
    private String packageName;
    private String appName;
    private Drawable appIcon;
    private long usageTimeMillis;
    private String category;

    public AppUsageInfo(String packageName, String appName, Drawable appIcon, long usageTimeMillis) {
        this.packageName = packageName;
        this.appName = appName;
        this.appIcon = appIcon;
        this.usageTimeMillis = usageTimeMillis;
        this.category = "Other";
    }

    public AppUsageInfo(String packageName, String appName, Drawable appIcon, long usageTimeMillis, String category) {
        this.packageName = packageName;
        this.appName = appName;
        this.appIcon = appIcon;
        this.usageTimeMillis = usageTimeMillis;
        this.category = category;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public Drawable getAppIcon() {
        return appIcon;
    }

    public void setAppIcon(Drawable appIcon) {
        this.appIcon = appIcon;
    }

    public long getUsageTimeMillis() {
        return usageTimeMillis;
    }

    public void setUsageTimeMillis(long usageTimeMillis) {
        this.usageTimeMillis = usageTimeMillis;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    /**
     * Returns formatted usage time string (e.g., "1h 30m" or "45 minutes")
     */
    public String getFormattedUsageTime() {
        long totalMinutes = usageTimeMillis / (1000 * 60);
        
        if (totalMinutes < 1) {
            return "< 1 minute";
        }
        
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        
        if (hours > 0) {
            if (minutes > 0) {
                return hours + "h " + minutes + "m";
            }
            return hours + " hour" + (hours > 1 ? "s" : "");
        }
        
        return minutes + " minute" + (minutes > 1 ? "s" : "");
    }

    /**
     * Returns short formatted time (e.g., "1h 30m" or "45m")
     */
    public String getShortFormattedTime() {
        long totalMinutes = usageTimeMillis / (1000 * 60);
        
        if (totalMinutes < 1) {
            return "0m";
        }
        
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        
        if (hours > 0) {
            if (minutes > 0) {
                return hours + "h " + minutes + "m";
            }
            return hours + "h";
        }
        
        return minutes + "m";
    }

    /**
     * Returns usage time in minutes
     */
    public long getUsageTimeMinutes() {
        return usageTimeMillis / (1000 * 60);
    }

    /**
     * Returns usage time in hours (as float)
     */
    public float getUsageTimeHours() {
        return usageTimeMillis / (1000f * 60f * 60f);
    }
}
