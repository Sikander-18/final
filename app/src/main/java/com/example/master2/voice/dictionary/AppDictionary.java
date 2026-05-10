package com.example.master2.voice.dictionary;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dynamic app dictionary that syncs from the child device's installed app list.
 * Falls back to a hardcoded list of popular apps if Firebase data isn't available yet.
 * 
 * Each entry maps a normalized alias (e.g. "instagram", "insta") to the full app name.
 * This is used during token classification to identify app name tokens.
 */
public class AppDictionary {

    private static final String TAG = "AppDictionary";

    // Maps normalized alias -> canonical app name
    private final Map<String, String> aliasToName = new HashMap<>();

    // Maps canonical app name -> package name (when available from Firebase)
    private final Map<String, String> nameToPackage = new HashMap<>();

    private boolean isLoaded = false;

    public AppDictionary() {
        loadHardcodedDefaults();
    }

    /**
     * Hardcoded popular apps. These are always available even before Firebase sync.
     * The keys are all possible aliases a user might say.
     */
    private void loadHardcodedDefaults() {
        // Social Media
        addAlias("instagram", "Instagram");
        addAlias("insta", "Instagram");
        addAlias("ig", "Instagram");
        addAlias("facebook", "Facebook");
        addAlias("fb", "Facebook");
        addAlias("whatsapp", "WhatsApp");
        addAlias("wa", "WhatsApp");
        addAlias("snapchat", "Snapchat");
        addAlias("snap", "Snapchat");
        addAlias("twitter", "Twitter");
        addAlias("x", "Twitter");
        addAlias("telegram", "Telegram");
        addAlias("tg", "Telegram");
        addAlias("threads", "Threads");

        // Video / Streaming
        addAlias("youtube", "YouTube");
        addAlias("yt", "YouTube");
        addAlias("netflix", "Netflix");
        addAlias("hotstar", "Hotstar");
        addAlias("prime", "Amazon Prime Video");
        addAlias("primevideo", "Amazon Prime Video");

        // Gaming
        addAlias("pubg", "PUBG Mobile");
        addAlias("bgmi", "BGMI");
        addAlias("freefire", "Free Fire");
        addAlias("ff", "Free Fire");
        addAlias("minecraft", "Minecraft");
        addAlias("roblox", "Roblox");
        addAlias("candycrush", "Candy Crush Saga");
        addAlias("candy crush", "Candy Crush Saga");
        addAlias("coc", "Clash of Clans");
        addAlias("clash", "Clash of Clans");

        // Utility / Other
        addAlias("chrome", "Chrome");
        addAlias("browser", "Chrome");
        addAlias("maps", "Google Maps");
        addAlias("gmail", "Gmail");
        addAlias("tiktok", "TikTok");
        addAlias("spotify", "Spotify");
        addAlias("pinterest", "Pinterest");
        addAlias("reddit", "Reddit");
        addAlias("discord", "Discord");
    }

    private void addAlias(String alias, String canonicalName) {
        aliasToName.put(alias.toLowerCase(Locale.US), canonicalName);
    }

    /**
     * Sync app list from Firebase for a specific child device.
     * This overlays the hardcoded defaults with real data.
     */
    public void syncFromFirebase(String childDeviceId) {
        if (childDeviceId == null || childDeviceId.isEmpty()) return;

        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("device_apps").child(childDeviceId);

        ref.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot appSnap : snapshot.getChildren()) {
                    String packageName = getStringValue(appSnap, "packageName");
                    String appName = getStringValue(appSnap, "name");

                    if (packageName == null || appName == null) continue;

                    String normalized = appName.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", "").trim();
                    
                    // Add the full name
                    aliasToName.put(normalized, appName);
                    nameToPackage.put(appName, packageName);

                    // Add the first word as a shortcut (e.g., "candy" for "Candy Crush Saga")
                    String[] words = normalized.split("\\s+");
                    if (words.length > 1) {
                        aliasToName.put(words[0], appName);
                    }
                }
                isLoaded = true;
                Log.d(TAG, "Synced " + snapshot.getChildrenCount() + " apps from Firebase");
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Failed to sync apps: " + error.getMessage());
            }
        });
    }

    private String getStringValue(DataSnapshot snapshot, String key) {
        Object val = snapshot.child(key).getValue();
        if (val != null) return val.toString();
        if ("packageName".equals(key)) return snapshot.getKey();
        return null;
    }

    /**
     * Try to match a single token against the app dictionary.
     * Returns the canonical app name if found, null otherwise.
     */
    public String matchSingleToken(String token) {
        return aliasToName.get(token.toLowerCase(Locale.US));
    }

    /**
     * Try to match a multi-token phrase against the app dictionary.
     * This handles cases like "candy crush" (2 tokens = 1 app).
     * Uses greedy longest-match-first strategy.
     * 
     * @param tokens The full list of tokens
     * @param startIndex Where to start looking
     * @return A MatchResult with the app name and how many tokens were consumed, or null
     */
    public MatchResult matchLongest(List<String> tokens, int startIndex) {
        // Try progressively shorter phrases starting from max length
        int maxLen = Math.min(4, tokens.size() - startIndex); // Max 4-word app names

        for (int len = maxLen; len >= 1; len--) {
            StringBuilder phrase = new StringBuilder();
            for (int i = startIndex; i < startIndex + len; i++) {
                if (phrase.length() > 0) phrase.append(" ");
                phrase.append(tokens.get(i));
            }
            String match = aliasToName.get(phrase.toString().toLowerCase(Locale.US));
            if (match != null) {
                return new MatchResult(match, len);
            }
        }
        return null;
    }

    /**
     * Get all known aliases (for debugging or display).
     */
    public List<String> getAllAliases() {
        return new ArrayList<>(aliasToName.keySet());
    }

    public boolean isLoaded() {
        return isLoaded;
    }

    /**
     * Result of a multi-token match attempt.
     */
    public static class MatchResult {
        public final String appName;
        public final int tokensConsumed;

        public MatchResult(String appName, int tokensConsumed) {
            this.appName = appName;
            this.tokensConsumed = tokensConsumed;
        }
    }
}
