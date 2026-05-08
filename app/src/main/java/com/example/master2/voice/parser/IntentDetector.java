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
        put("block", VoiceCommandIntent.ACTION_BLOCK, 1.0f);
        put("lock", VoiceCommandIntent.ACTION_BLOCK, 0.9f);
        put("bandh", VoiceCommandIntent.ACTION_BLOCK, 0.8f);
        put("band", VoiceCommandIntent.ACTION_BLOCK, 0.8f);
        put("rok", VoiceCommandIntent.ACTION_BLOCK, 0.7f);
        put("restrict", VoiceCommandIntent.ACTION_BLOCK, 1.0f);

        put("unblock", VoiceCommandIntent.ACTION_UNBLOCK, 1.0f);
        put("unlock", VoiceCommandIntent.ACTION_UNBLOCK, 1.0f);
        put("allow", VoiceCommandIntent.ACTION_UNBLOCK, 0.9f);
        put("khol", VoiceCommandIntent.ACTION_UNBLOCK, 0.9f);
        put("chalu", VoiceCommandIntent.ACTION_UNBLOCK, 0.8f);
        put("open", VoiceCommandIntent.ACTION_UNBLOCK, 0.5f);
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
            float current = scoreByAction.containsKey(weighted.action) ? scoreByAction.get(weighted.action) : 0f;
            scoreByAction.put(weighted.action, Math.max(current, weighted.weight));
        }

        String bestAction = null;
        float bestScore = 0.0f;
        for (Map.Entry<String, Float> entry : scoreByAction.entrySet()) {
            if (entry.getValue() > bestScore) {
                bestAction = entry.getKey();
                bestScore = entry.getValue();
            }
        }
        return new IntentResult(bestAction, bestScore);
    }
}
