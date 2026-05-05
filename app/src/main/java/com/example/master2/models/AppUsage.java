package com.example.master2.models;

public class AppUsage {
    private String packageName;
    private String appName;
    private long usageTime;
    private String category;
    private boolean blocked;

    // Default constructor required for Firebase
    public AppUsage() {
    }

    public AppUsage(String packageName, String appName, long usageTime, String category, boolean blocked) {
        this.packageName = packageName;
        this.appName = appName;
        this.usageTime = usageTime;
        this.category = category;
        this.blocked = blocked;
    }

    // Getters and Setters
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public long getUsageTime() { return usageTime; }
    public void setUsageTime(long usageTime) { this.usageTime = usageTime; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean blocked) { this.blocked = blocked; }
}