package com.example.master2.models;

import java.util.List;
import com.example.master2.models.AppUsage;

public class DeviceInfo {
    private String deviceId;
    private String deviceName;
    private String parentId;
    private String childId;
    private boolean isOnline;
    private List<AppUsage> appUsageList;
    private boolean focusModeEnabled;

    public DeviceInfo() {}

    public DeviceInfo(String deviceId, String deviceName, String parentId, String childId) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.parentId = parentId;
        this.childId = childId;
        this.isOnline = false;
        this.focusModeEnabled = false;
    }

    // Getters and Setters
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public String getChildId() { return childId; }
    public void setChildId(String childId) { this.childId = childId; }

    public boolean isOnline() { return isOnline; }
    public void setOnline(boolean online) { isOnline = online; }

    public List<AppUsage> getAppUsageList() { return appUsageList; }
    public void setAppUsageList(List<AppUsage> appUsageList) { this.appUsageList = appUsageList; }

    public boolean isFocusModeEnabled() { return focusModeEnabled; }
    public void setFocusModeEnabled(boolean focusModeEnabled) { this.focusModeEnabled = focusModeEnabled; }
}
