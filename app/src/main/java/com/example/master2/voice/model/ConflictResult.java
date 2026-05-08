package com.example.master2.voice.model;

import java.util.ArrayList;
import java.util.List;

public class ConflictResult {
    public static final String POLICY_OVERRIDE = "override_existing";
    public static final String POLICY_KEEP_EXISTING = "keep_existing";
    public static final String POLICY_MERGE = "merge";

    public boolean hasConflict = false;
    public String chosenPolicy = POLICY_OVERRIDE;
    public List<String> reasons = new ArrayList<>();
}
