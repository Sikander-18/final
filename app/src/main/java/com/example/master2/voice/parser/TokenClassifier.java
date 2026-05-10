package com.example.master2.voice.parser;

import android.util.Log;

import com.example.master2.voice.dictionary.AppDictionary;
import com.example.master2.voice.dictionary.DictionaryManager;
import com.example.master2.voice.dictionary.IntentDictionary;
import com.example.master2.voice.model.VoiceCommandIntent;

import java.util.ArrayList;
import java.util.List;

/**
 * Classifies tokens against dictionaries to extract intent and app names.
 * 
 * Pipeline:
 *   1. Takes a list of normalized tokens
 *   2. Classifies each token using dictionary lookups (exact match only)
 *   3. Uses greedy longest-match for app names (handles multi-word names)
 *   4. Returns structured classification results
 * 
 * This is purely deterministic — no fuzzy matching, no ML.
 */
public final class TokenClassifier {

    private static final String TAG = "TokenClassifier";

    public enum TokenType {
        BLOCK_INTENT,
        UNBLOCK_INTENT,
        APP_NAME,
        TIME_KEYWORD,
        TIME_UNIT,
        TIME_VALUE,    // numeric tokens like "8", "7:02"
        CONNECTOR,
        FILLER,
        UNKNOWN
    }

    public static class ClassifiedToken {
        public final String originalToken;
        public final TokenType type;
        public final String resolvedValue; // e.g., canonical app name

        public ClassifiedToken(String originalToken, TokenType type, String resolvedValue) {
            this.originalToken = originalToken;
            this.type = type;
            this.resolvedValue = resolvedValue;
        }

        @Override
        public String toString() {
            return "[" + originalToken + " → " + type + (resolvedValue != null ? " (" + resolvedValue + ")" : "") + "]";
        }
    }

    public static class ClassificationResult {
        public String detectedAction = null;
        public final List<String> detectedApps = new ArrayList<>();
        public final List<ClassifiedToken> allTokens = new ArrayList<>();

        public boolean hasAction() { return detectedAction != null; }
        public boolean hasApps() { return !detectedApps.isEmpty(); }
    }

    private TokenClassifier() {}

    /**
     * Classify all tokens from a normalized command string.
     * 
     * @param tokens List of normalized tokens (already through CommandNormalizer)
     * @return Classification result with detected action and app names
     */
    public static ClassificationResult classify(List<String> tokens) {
        ClassificationResult result = new ClassificationResult();
        AppDictionary appDict = DictionaryManager.getInstance().getAppDictionary();

        int i = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);

            // Skip pure numeric or time-pattern tokens (handled by TimeEntityExtractor)
            if (token.matches("\\d+") || token.matches("\\d{1,2}:\\d{2}")) {
                result.allTokens.add(new ClassifiedToken(token, TokenType.TIME_VALUE, null));
                i++;
                continue;
            }

            // 1. Try LONGEST APP MATCH first (greedy, handles multi-word names)
            AppDictionary.MatchResult appMatch = appDict.matchLongest(tokens, i);
            if (appMatch != null) {
                // Build the original phrase for logging
                StringBuilder phrase = new StringBuilder();
                for (int j = i; j < i + appMatch.tokensConsumed; j++) {
                    if (phrase.length() > 0) phrase.append(" ");
                    phrase.append(tokens.get(j));
                }
                result.allTokens.add(new ClassifiedToken(phrase.toString(), TokenType.APP_NAME, appMatch.appName));
                if (!result.detectedApps.contains(appMatch.appName)) {
                    result.detectedApps.add(appMatch.appName);
                }
                i += appMatch.tokensConsumed;
                continue;
            }

            // 2. Check BLOCK intent
            if (IntentDictionary.BLOCK_WORDS.contains(token)) {
                result.allTokens.add(new ClassifiedToken(token, TokenType.BLOCK_INTENT, null));
                // Only override if we don't already have a higher-priority action
                if (result.detectedAction == null) {
                    result.detectedAction = VoiceCommandIntent.ACTION_BLOCK;
                }
                i++;
                continue;
            }

            // 3. Check UNBLOCK intent
            if (IntentDictionary.UNBLOCK_WORDS.contains(token)) {
                result.allTokens.add(new ClassifiedToken(token, TokenType.UNBLOCK_INTENT, null));
                if (result.detectedAction == null) {
                    result.detectedAction = VoiceCommandIntent.ACTION_UNBLOCK;
                }
                i++;
                continue;
            }

            // 4. Check TIME KEYWORD
            if (IntentDictionary.TIME_KEYWORDS.contains(token)) {
                result.allTokens.add(new ClassifiedToken(token, TokenType.TIME_KEYWORD, null));
                i++;
                continue;
            }

            // 5. Check TIME UNIT
            if (IntentDictionary.TIME_UNITS.contains(token)) {
                result.allTokens.add(new ClassifiedToken(token, TokenType.TIME_UNIT, null));
                i++;
                continue;
            }

            // 6. Check CONNECTOR
            if (IntentDictionary.CONNECTORS.contains(token)) {
                result.allTokens.add(new ClassifiedToken(token, TokenType.CONNECTOR, null));
                i++;
                continue;
            }

            // 7. Check FILLER
            if (IntentDictionary.FILLER_WORDS.contains(token)) {
                result.allTokens.add(new ClassifiedToken(token, TokenType.FILLER, null));
                i++;
                continue;
            }

            // 8. UNKNOWN — this could be an unrecognized app name
            // Try a single-token app match as last resort (already tried multi, but explicit check)
            result.allTokens.add(new ClassifiedToken(token, TokenType.UNKNOWN, null));
            Log.d(TAG, "Unknown token: '" + token + "' — treating as potential app name");
            
            // Add unknown tokens as candidate app names (the resolver will verify later)
            if (!result.detectedApps.contains(token)) {
                result.detectedApps.add(token);
            }
            i++;
        }

        Log.d(TAG, "Classification: " + result.allTokens.toString());
        return result;
    }
}
