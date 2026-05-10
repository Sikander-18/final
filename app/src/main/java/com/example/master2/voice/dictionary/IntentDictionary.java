package com.example.master2.voice.dictionary;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Static dictionaries for intent detection and token classification.
 * All keywords are post-normalization (lowercase, Hinglish already normalized).
 * 
 * This class is the single source of truth for all known command keywords.
 * No ML, no fuzzy matching — pure deterministic token lookup.
 */
public final class IntentDictionary {

    private IntentDictionary() {}

    // ── BLOCK INTENT ────────────────────────────────────────────────────────
    public static final Set<String> BLOCK_WORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            // English
            "block", "lock", "restrict", "stop", "disable", "pause",
            // Hinglish
            "bandh", "band", "rok", "roko"
    )));

    // ── UNBLOCK INTENT ──────────────────────────────────────────────────────
    public static final Set<String> UNBLOCK_WORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            // English
            "unblock", "unlock", "allow", "enable", "resume", "start", "open",
            // Hinglish
            "khol", "kholo", "chalu"
    )));

    // ── FILLER WORDS (to be ignored during app extraction) ──────────────────
    public static final Set<String> FILLER_WORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "kar", "do", "de", "ko", "ne", "please", "kardo", "dena",
            "wapas", "access", "karo"
    )));

    // ── TIME KEYWORDS (signal that time info follows or is nearby) ──────────
    public static final Set<String> TIME_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "at", "baje", "bajke",
            "after", "baad", "later",
            "from", "to", "between", "till", "until",
            "se", "tak", "beech", "lekar",
            "am", "pm", "morning", "subah", "night", "shaam", "raat"
    )));

    // ── TIME UNIT WORDS ─────────────────────────────────────────────────────
    public static final Set<String> TIME_UNITS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "minute", "hour", "ghante", "ghanta"
    )));

    // ── CONNECTOR WORDS (for multi-app commands) ────────────────────────────
    public static final Set<String> CONNECTORS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "and", "aur"
    )));

    /**
     * Returns true if the token is any known keyword (not an app name).
     * Used to identify "leftover" tokens that are candidate app names.
     */
    public static boolean isKnownKeyword(String token) {
        return BLOCK_WORDS.contains(token)
                || UNBLOCK_WORDS.contains(token)
                || FILLER_WORDS.contains(token)
                || TIME_KEYWORDS.contains(token)
                || TIME_UNITS.contains(token)
                || CONNECTORS.contains(token);
    }
}
