package com.example.master2.voice.parser;

import android.util.Log;

import com.example.master2.voice.model.ScheduleSpec;
import com.example.master2.voice.model.VoiceCommandIntent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Main command processing pipeline.
 * 
 * Pipeline steps:
 *   1. Normalize  — Hinglish normalization (krr→kar, mins→minute, etc.)
 *   2. Tokenize   — Split into tokens
 *   3. Extract Time — Regex-based time extraction on the FULL normalized string
 *   4. Classify   — Dictionary-based token classification for intent + app names
 *   5. Validate   — Check that we have action + app + schedule
 * 
 * Key improvements:
 *   - Time extraction happens on the full string BEFORE token classification
 *   - App names are identified by dictionary lookup, not by "what's left after removing keywords"
 *   - No regex skip-lists that need constant updating
 *   - No "instagram contains am" confusion since token classification is exact-match
 */
public class CommandPipeline {

    private static final String TAG = "CommandPipeline";

    public VoiceCommandIntent process(String rawInput) {
        VoiceCommandIntent intent = new VoiceCommandIntent();
        intent.sourceText = rawInput;

        // Step 1: Normalize
        String normalized = CommandNormalizer.normalize(rawInput);
        intent.normalizedText = normalized;
        Log.d(TAG, "Step 1 - Normalized: '" + normalized + "'");

        // Step 2: Tokenize
        List<String> tokens = tokenize(normalized);
        Log.d(TAG, "Step 2 - Tokens: " + tokens);

        // Step 3: Extract Time (on full normalized string, BEFORE token classification)
        extractTime(intent);
        Log.d(TAG, "Step 3 - Schedule: " + intent.scheduleSpec.scheduleType 
                + " start=" + intent.scheduleSpec.startEpochMs);

        // Step 4: Classify tokens against dictionaries
        TokenClassifier.ClassificationResult classification = TokenClassifier.classify(tokens);
        
        // Apply classification results
        if (classification.hasAction()) {
            intent.action = classification.detectedAction;
        }
        if (classification.hasApps()) {
            intent.appAliases = new ArrayList<>(classification.detectedApps);
        }
        Log.d(TAG, "Step 4 - Action: " + intent.action + ", Apps: " + intent.appAliases);

        // Step 5: Validate
        validate(intent);
        Log.d(TAG, "Step 5 - Valid: " + intent.valid 
                + (intent.errorMessage != null ? ", Error: " + intent.errorMessage : ""));

        return intent;
    }

    private List<String> tokenize(String normalized) {
        String[] parts = normalized.split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }

    private void extractTime(VoiceCommandIntent intent) {
        TimeParseResult timeResult = TimeEntityExtractor.extract(intent.normalizedText);
        
        if (timeResult.hasRange) {
            intent.scheduleSpec.scheduleType = ScheduleSpec.TYPE_TIME_RANGE;
            intent.scheduleSpec.startEpochMs = timeResult.startTimeMs;
            intent.scheduleSpec.endEpochMs = timeResult.endTimeMs;
            return;
        }
        if (timeResult.isRelative) {
            intent.scheduleSpec.scheduleType = ScheduleSpec.TYPE_AT_TIME;
            intent.scheduleSpec.startEpochMs = timeResult.startTimeMs;
            return;
        }
        if (timeResult.isAbsolute) {
            intent.scheduleSpec.scheduleType = ScheduleSpec.TYPE_AT_TIME;
            intent.scheduleSpec.startEpochMs = timeResult.startTimeMs;
            return;
        }
        intent.scheduleSpec.scheduleType = ScheduleSpec.TYPE_IMMEDIATE;
    }

    private void validate(VoiceCommandIntent intent) {
        List<String> missing = new ArrayList<>();
        
        if (intent.action == null || intent.action.isEmpty()) {
            missing.add("action");
        }
        if (intent.appAliases == null || intent.appAliases.isEmpty()) {
            missing.add("app");
        }
        if (intent.scheduleSpec == null || !intent.scheduleSpec.isValid()) {
            missing.add("time");
        }

        if (missing.isEmpty()) {
            intent.valid = true;
            intent.needsClarification = false;
        } else {
            intent.valid = false;
            intent.needsClarification = true;
            intent.errorMessage = "Could not parse: missing " + String.join(", ", missing);
        }
    }
}
