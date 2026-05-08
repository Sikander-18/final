package com.example.master2.voice.model;

public class ResolvedAppTarget {
    public String childDeviceId;
    public String packageName;
    public String appName;
    public String matchedAlias;
    public double confidence = 0.0;
    public boolean ambiguous = false;

    public ResolvedAppTarget() {
    }

    public ResolvedAppTarget(String childDeviceId, String packageName, String appName, String matchedAlias,
            double confidence, boolean ambiguous) {
        this.childDeviceId = childDeviceId;
        this.packageName = packageName;
        this.appName = appName;
        this.matchedAlias = matchedAlias;
        this.confidence = confidence;
        this.ambiguous = ambiguous;
    }
}
