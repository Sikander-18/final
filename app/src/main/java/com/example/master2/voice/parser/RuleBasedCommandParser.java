package com.example.master2.voice.parser;

import com.example.master2.voice.model.ScheduleSpec;
import com.example.master2.voice.model.VoiceCommandIntent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class RuleBasedCommandParser {
    public VoiceCommandIntent parse(String commandText) {
        VoiceCommandIntent intent = new VoiceCommandIntent();
        intent.sourceText = commandText;
        intent.normalizedText = CommandNormalizer.normalize(commandText);

        parseAction(intent);
        parseSchedule(intent);
        parseApps(intent);
        validate(intent);
        return intent;
    }

    private void parseAction(VoiceCommandIntent intent) {
        IntentResult result = IntentDetector.detect(intent.normalizedText);
        intent.action = result.action;
    }

    private void parseSchedule(VoiceCommandIntent intent) {
        TimeParseResult timeResult = TimeEntityExtractor.extract(intent.normalizedText);
        if (timeResult.hasRange) {
            intent.scheduleSpec.scheduleType = ScheduleSpec.TYPE_TIME_RANGE;
            intent.scheduleSpec.startEpochMs = timeResult.startTimeMs;
            intent.scheduleSpec.endEpochMs = timeResult.endTimeMs;
            intent.scheduleSpec.crossesMidnight = true;
            return;
        }
        if (timeResult.isRelative) {
            long delayMs = timeResult.startTimeMs - System.currentTimeMillis();
            intent.scheduleSpec.scheduleType = ScheduleSpec.TYPE_AFTER_DURATION;
            intent.scheduleSpec.delayMs = Math.max(delayMs, 0L);
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

    private void parseApps(VoiceCommandIntent intent) {
        String text = intent.normalizedText;
        text = text.replaceAll("\\b(block|unblock|lock|unlock|at|after|from|to|se|tak|and|am|pm|night|morning|baje|minute|minutes|hour|hours|ghante|kar)\\b",
                " ");
        text = text.replaceAll("\\d{1,2}(:\\d{2})?", " ");
        text = text.replaceAll("\\s+", " ").trim();

        Set<String> aliases = new LinkedHashSet<>();
        if (!text.isEmpty()) {
            String[] parts = text.split("\\band\\b");
            for (String part : parts) {
                String cleaned = part.trim();
                if (!cleaned.isEmpty()) {
                    aliases.add(cleaned);
                }
            }
        }
        intent.appAliases = new ArrayList<>(aliases);
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
            return;
        }
        intent.valid = false;
        intent.needsClarification = true;
        intent.errorMessage = "Could not parse: missing " + String.join(", ", missing);
    }
}
