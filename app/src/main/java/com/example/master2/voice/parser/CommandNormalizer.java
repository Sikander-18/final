package com.example.master2.voice.parser;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class CommandNormalizer {
    private static final Map<String, String> NORMALIZATION_MAP = new HashMap<>();

    static {
        // Hinglish Normalization Rules (Strict)
        NORMALIZATION_MAP.put("krr", "kar");
        NORMALIZATION_MAP.put("kr", "kar");
        NORMALIZATION_MAP.put("krdo", "kar");
        NORMALIZATION_MAP.put("krde", "kar");
        NORMALIZATION_MAP.put("karo", "kar");
        NORMALIZATION_MAP.put("dena", "de");
        NORMALIZATION_MAP.put("bajke", "baje");
        NORMALIZATION_MAP.put("mins", "minute");
        NORMALIZATION_MAP.put("min", "minute");
        NORMALIZATION_MAP.put("hrs", "hour");
        NORMALIZATION_MAP.put("hr", "hour");
        
        // Short forms and connectors
        NORMALIZATION_MAP.put("insta", "instagram");
        NORMALIZATION_MAP.put("yt", "youtube");
        NORMALIZATION_MAP.put("fb", "facebook");
        NORMALIZATION_MAP.put("snap", "snapchat");
        NORMALIZATION_MAP.put("aur", "and");
        NORMALIZATION_MAP.put("tak", "to");
        NORMALIZATION_MAP.put("se", "from");
    }

    private CommandNormalizer() {
    }

    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        // Preserve Hindi script characters if they exist, though not explicitly handled in keywords yet
        String normalized = input.toLowerCase(Locale.US).trim();
        normalized = normalized.replaceAll("[^a-zA-Z0-9\\u0900-\\u097F: ]", " ");
        normalized = normalized.replaceAll("([a-z])\\1{2,}", "$1$1");

        String[] tokens = normalized.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (NORMALIZATION_MAP.containsKey(token)) {
                sb.append(NORMALIZATION_MAP.get(token)).append(" ");
            } else {
                sb.append(token).append(" ");
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }
}
