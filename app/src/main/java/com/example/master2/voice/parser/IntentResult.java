package com.example.master2.voice.parser;

import com.example.master2.voice.model.VoiceCommandIntent;

public class IntentResult {
    public final String action;
    public final float confidence;

    public IntentResult(String action, float confidence) {
        this.action = action;
        this.confidence = confidence;
    }

    public boolean isKnown() {
        return VoiceCommandIntent.ACTION_BLOCK.equals(action) || VoiceCommandIntent.ACTION_UNBLOCK.equals(action);
    }
}
