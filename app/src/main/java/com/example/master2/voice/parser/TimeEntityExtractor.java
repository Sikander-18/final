package com.example.master2.voice.parser;

import android.util.Log;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts time information from normalized command text using regex patterns.
 * 
 * Handles:
 *   - Relative time: "5 minute baad", "after 10 minute"
 *   - Absolute time: "at 8", "7 baje", "3:30 pm"
 *   - Time ranges: "from 7 to 8", "7 baje se 8 baje tak"
 * 
 * IMPORTANT: This runs on the FULL normalized string before token classification.
 * It uses word-boundary matching for AM/PM to avoid the "instagram contains am" bug.
 */
public final class TimeEntityExtractor {
    private static final String TAG = "TimeEntityExtractor";

    private static final Pattern REL_MIN_PATTERN = Pattern
            .compile("(\\d+)\\s*(?:minute)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REL_HR_PATTERN = Pattern
            .compile("(\\d+)\\s*(?:hour|ghante|ghanta)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABS_TIME_PATTERN = Pattern
            .compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|baje)?", Pattern.CASE_INSENSITIVE);

    // Range patterns: "from X to Y", "X se Y tak", "X to Y"
    private static final Pattern RANGE_PATTERN = Pattern.compile(
            "(?:from|between|se)?\\s*(\\d{1,2}(?::\\d{2})?(?:\\s*(?:am|pm|baje))?)\\s+(?:to|till|until|tak|-)\\s+(\\d{1,2}(?::\\d{2})?(?:\\s*(?:am|pm|baje))?)",
            Pattern.CASE_INSENSITIVE);

    private TimeEntityExtractor() {}

    public static TimeParseResult extract(String normalizedText) {
        TimeParseResult result = new TimeParseResult();
        if (normalizedText == null || normalizedText.isEmpty()) return result;

        Log.d(TAG, "Extracting time from: '" + normalizedText + "'");

        // Priority 1: Range
        if (checkRange(normalizedText, result)) {
            result.hasRange = true;
            result.requiresSchedule = true;
            Log.d(TAG, "Found RANGE: " + result.startTimeMs + " → " + result.endTimeMs);
            return result;
        }

        // Priority 2: Relative
        if (checkRelative(normalizedText, result)) {
            result.isRelative = true;
            result.requiresSchedule = true;
            Log.d(TAG, "Found RELATIVE: " + result.startTimeMs);
            return result;
        }

        // Priority 3: Absolute
        if (checkAbsolute(normalizedText, result)) {
            result.isAbsolute = true;
            result.requiresSchedule = true;
            Log.d(TAG, "Found ABSOLUTE: " + result.startTimeMs);
        }

        return result;
    }

    private static boolean checkRange(String text, TimeParseResult result) {
        Matcher matcher = RANGE_PATTERN.matcher(text);
        if (!matcher.find()) return false;

        String startStr = matcher.group(1).trim();
        String endStr = matcher.group(2).trim();

        long startMs = parseSingleTime(startStr, text);
        long endMs = parseSingleTime(endStr, text);

        if (startMs <= 0L || endMs <= 0L) return false;

        result.startTimeMs = startMs;
        result.endTimeMs = endMs;

        // Auto-fix: if end <= start, it must be the next day
        if (result.endTimeMs <= result.startTimeMs) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(result.endTimeMs);
            cal.add(Calendar.DAY_OF_YEAR, 1);
            result.endTimeMs = cal.getTimeInMillis();
        }
        return true;
    }

    private static boolean checkRelative(String text, TimeParseResult result) {
        // Only trigger if a relative keyword exists as a WORD
        if (!(containsWord(text, "baad") || containsWord(text, "after")
                || containsWord(text, "later") || containsWord(text, "in"))) {
            return false;
        }

        Calendar cal = Calendar.getInstance();
        boolean found = false;

        Matcher hrMatcher = REL_HR_PATTERN.matcher(text);
        if (hrMatcher.find()) {
            cal.add(Calendar.HOUR_OF_DAY, Integer.parseInt(hrMatcher.group(1)));
            found = true;
        }

        Matcher minMatcher = REL_MIN_PATTERN.matcher(text);
        if (minMatcher.find()) {
            cal.add(Calendar.MINUTE, Integer.parseInt(minMatcher.group(1)));
            found = true;
        }

        if (!found) return false;
        result.startTimeMs = cal.getTimeInMillis();
        return true;
    }

    private static boolean checkAbsolute(String text, TimeParseResult result) {
        // Only trigger if a time keyword exists as a WORD (not inside "instagram")
        if (!(containsWord(text, "at") || containsWord(text, "baje")
                || containsWord(text, "pm") || containsWord(text, "am"))) {
            return false;
        }

        Matcher matcher = ABS_TIME_PATTERN.matcher(text);
        if (!matcher.find()) return false;

        long timeMs = parseSingleTime(matcher.group(0), text);
        if (timeMs <= 0L) return false;
        result.startTimeMs = timeMs;
        return true;
    }

    /**
     * Check if a word exists as a standalone token in the text.
     * Uses word boundaries to prevent "instagram" matching "am".
     */
    private static boolean containsWord(String text, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE)
                .matcher(text).find();
    }

    /**
     * Parse a single time string (e.g., "7", "7:30", "8 pm", "3 baje") into epoch millis.
     * 
     * Smart AM/PM inference:
     *   - If user says "pm" or "am" explicitly, use that.
     *   - If context has "night"/"shaam"/"raat", treat as PM.
     *   - If context has "morning"/"subah", treat as AM.
     *   - Otherwise, pick the NEXT logical occurrence (try current interpretation,
     *     if past try +12h, if still past try tomorrow).
     */
    private static long parseSingleTime(String timeText, String fullContext) {
        try {
            Matcher matcher = ABS_TIME_PATTERN.matcher(timeText);
            if (!matcher.find()) return 0L;

            int hour = Integer.parseInt(matcher.group(1));
            int minute = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
            String marker = (matcher.group(3) != null ? matcher.group(3).toLowerCase() : "").trim();

            Calendar cal = Calendar.getInstance();
            long now = System.currentTimeMillis();

            // Explicit marker from the time text itself (safe — this is just the number part)
            boolean isExplicitPm = marker.equals("pm");
            boolean isExplicitAm = marker.equals("am");
            
            // Context-based inference using word boundaries (safe — won't match "instagram")
            if (!isExplicitPm && !isExplicitAm) {
                isExplicitPm = containsWord(fullContext, "pm")
                        || containsWord(fullContext, "night")
                        || containsWord(fullContext, "shaam")
                        || containsWord(fullContext, "raat");
                isExplicitAm = containsWord(fullContext, "am")
                        || containsWord(fullContext, "morning")
                        || containsWord(fullContext, "subah");
            }

            // Apply AM/PM
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);

            if (isExplicitPm && hour < 12) {
                cal.set(Calendar.HOUR_OF_DAY, hour + 12);
            } else if (isExplicitAm && hour == 12) {
                cal.set(Calendar.HOUR_OF_DAY, 0);
            } else if (!isExplicitAm && !isExplicitPm && hour < 12) {
                // SMART INFERENCE: no marker, pick the NEAREST future occurrence
                cal.set(Calendar.HOUR_OF_DAY, hour);
                cal.set(Calendar.MINUTE, minute);
                if (cal.getTimeInMillis() <= now) {
                    // AM is past — try PM
                    cal.set(Calendar.HOUR_OF_DAY, hour + 12);
                    if (cal.getTimeInMillis() <= now) {
                        // PM is also past — reset to AM and go to tomorrow
                        cal.set(Calendar.HOUR_OF_DAY, hour);
                        cal.add(Calendar.DAY_OF_YEAR, 1);
                    }
                }
            } else {
                cal.set(Calendar.HOUR_OF_DAY, hour);
            }

            cal.set(Calendar.MINUTE, minute);

            // If still in the past, it must be tomorrow
            if (cal.getTimeInMillis() <= now) {
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }

            Log.d(TAG, "Parsed [" + timeText + "] → " + cal.getTime().toString());
            return cal.getTimeInMillis();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing: " + timeText, e);
            return 0L;
        }
    }
}
