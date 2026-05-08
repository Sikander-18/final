package com.example.master2.voice.model;

import java.util.ArrayList;
import java.util.List;

public class AssistantRule {
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_PAUSED = "paused";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    public String ruleId;
    public String parentUserId;
    public List<String> targetChildIds = new ArrayList<>();
    public List<String> apps = new ArrayList<>();
    public String action;
    public String scheduleType;
    public long startEpochMs;
    public long endEpochMs;
    public String sourceText;
    public String status = STATUS_ACTIVE;
    public String createdBy = "text";
    public String createdAtIso;
    public long createdAtEpochMs;
    public String conflictPolicy = ConflictResult.POLICY_OVERRIDE;
    public String source = "voice_assistant";
    public boolean executedStart = false;
    public boolean executedEnd = false;

    public AssistantRule() {
    }
}
