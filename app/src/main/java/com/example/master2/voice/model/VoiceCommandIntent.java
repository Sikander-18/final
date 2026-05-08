package com.example.master2.voice.model;

import java.util.ArrayList;
import java.util.List;

public class VoiceCommandIntent {
    public static final String ACTION_BLOCK = "block";
    public static final String ACTION_UNBLOCK = "unblock";

    public String sourceText;
    public String normalizedText;
    public String action;
    public List<String> appAliases = new ArrayList<>();
    public ScheduleSpec scheduleSpec = new ScheduleSpec();
    public boolean valid = false;
    public String errorMessage;
    public boolean needsClarification = false;
}
