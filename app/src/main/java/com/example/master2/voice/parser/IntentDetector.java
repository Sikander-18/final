package com.example.master2.voice.parser;

import com.example.master2.voice.model.VoiceCommandIntent;
import java.util.HashMap;
import java.util.Map;

public final class IntentDetector {
    private static final Map<String, WeightedAction> WEIGHTS = new HashMap<>();

    private static final class WeightedAction {
        final String action;
        final float weight;

        WeightedAction(String action, float weight) {
            this.action = action;
            this.weight = weight;
        }
    }

    static {
        // English BLOCK
        put("block", VoiceCommandIntent.ACTION_BLOCK, 1.0f);
        put("lock", VoiceCommandIntent.ACTION_BLOCK, 0.9f);
        put("restrict", VoiceCommandIntent.ACTION_BLOCK, 1.0f);
        put("stop", VoiceCommandIntent.ACTION_BLOCK, 0.8f);
        put("disable", VoiceCommandIntent.ACTION_BLOCK, 0.9f);
        put("pause", VoiceCommandIntent.ACTION_BLOCK, 0.8f);

        // Hindi/Hinglish BLOCK
        put("bandh", VoiceCommandIntent.ACTION_BLOCK, 1.0f);
        put("band", VoiceCommandIntent.ACTION_BLOCK, 1.0f);
        put("rok", VoiceCommandIntent.ACTION_BLOCK, 0.9f);
        put("roko", VoiceCommandIntent.ACTION_BLOCK, 0.9f);

        // English UNBLOCK
        put("unblock", VoiceCommandIntent.ACTION_UNBLOCK, 1.0f);
        put("unlock", VoiceCommandIntent.ACTION_UNBLOCK, 1.0f);
        put("allow", VoiceCommandIntent.ACTION_UNBLOCK, 0.9f);
        put("enable", VoiceCommandIntent.ACTION_UNBLOCK, 0.9f);
        put("resume", VoiceCommandIntent.ACTION_UNBLOCK, 0.8f);
        put("start", VoiceCommandIntent.ACTION_UNBLOCK, 0.7f);
        put("open", VoiceCommandIntent.ACTION_UNBLOCK, 0.5f);

        // Hindi/Hinglish UNBLOCK
        put("khol", VoiceCommandIntent.ACTION_UNBLOCK, 1.0f);
        put("kholo", VoiceCommandIntent.ACTION_UNBLOCK, 1.0f);
        put("chalu", VoiceCommandIntent.ACTION_UNBLOCK, 0.9f);
        put("access", VoiceCommandIntent.ACTION_UNBLOCK, 0.8f);
    }

    private IntentDetector() {
    }

    private static void put(String token, String action, float weight) {
        WEIGHTS.put(token, new WeightedAction(action, weight));
    }

    public static IntentResult detect(String normalizedText) {
        if (normalizedText == null || normalizedText.isEmpty()) {
            return new IntentResult(null, 0.0f);
        }

        Map<String, Float> scoreByAction = new HashMap<>();
        String[] tokens = normalizedText.split("\\s+");
        for (String token : tokens) {
            WeightedAction weighted = WEIGHTS.get(token);
            if (weighted == null) {
                continue;
            }
            float current = scoreByAction.getOrDefault(weighted.action, 0f);
            scoreByAction.put(weighted.action, Math.max(current, weighted.weight));
        }

        String bestAction = null;
        float bestScore = 0.0f;
        for (Map.Entry<String, Float> entry : scoreByAction.entrySet()) {
            if (entry.getValue() > bestScore) {
                bestScore = entry.getValue();
                bestAction = entry.getKey();
            }
        }

        return new IntentResult(bestAction, bestScore);
    }
}
