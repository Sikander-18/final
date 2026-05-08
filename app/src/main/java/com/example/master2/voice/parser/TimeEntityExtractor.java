package com.example.master2.voice.parser;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeEntityExtractor {
    // English/Hinglish token patterns (post-normalization)
    private static final Pattern REL_MIN_PATTERN = Pattern
            .compile("(\\d+)\\s*(?:minute|min)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REL_HR_PATTERN = Pattern
            .compile("(\\d+)\\s*(?:hour|ghante|ghanta)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABS_TIME_PATTERN = Pattern
            .compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|baje|at)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANGE_PATTERN = Pattern.compile(
            "(?:from|between)?\\s*([\\d:apm\\s]+?)\\s*(?:to|till|until|and|-)\\s*([\\d:apm\\s]+)",
            Pattern.CASE_INSENSITIVE);

    private TimeEntityExtractor() {
    }

    public static TimeParseResult extract(String normalizedText) {
        TimeParseResult result = new TimeParseResult();
        if (normalizedText == null || normalizedText.isEmpty()) {
            return result;
        }

        // Check for range FIRST (highest priority)
        if (checkRange(normalizedText, result)) {
            result.hasRange = true;
            result.requiresSchedule = true;
            return result;
        }

        // Check for relative (after X mins)
        if (checkRelative(normalizedText, result)) {
            result.isRelative = true;
            result.requiresSchedule = true;
            return result;
        }

        // Check for absolute (at 4pm)
        if (checkAbsolute(normalizedText, result)) {
            result.isAbsolute = true;
            result.requiresSchedule = true;
        }

        return result;
    }

    private static boolean checkRange(String text, TimeParseResult result) {
        Matcher matcher = RANGE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return false;
        }
        String start = matcher.group(1).trim();
        String end = matcher.group(2).trim();
        
        if (!start.matches(".*\\d.*") || !end.matches(".*\\d.*")) {
            return false;
        }

        long startMs = parseSingleTime(start, text);
        long endMs = parseSingleTime(end, text);

        if (startMs <= 0L || endMs <= 0L) {
            return false;
        }

        result.startTimeMs = startMs;
        result.endTimeMs = endMs;

        // Auto-fix day rollover if end < start
        if (result.endTimeMs <= result.startTimeMs) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(result.endTimeMs);
            cal.add(Calendar.DAY_OF_YEAR, 1);
            result.endTimeMs = cal.getTimeInMillis();
        }
        return true;
    }

    private static boolean checkRelative(String text, TimeParseResult result) {
        // Strict keyword check for relative time
        if (!(text.contains("baad") || text.contains("after") || text.contains("later") || text.contains(" in "))) {
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

        if (!found) {
            return false;
        }
        result.startTimeMs = cal.getTimeInMillis();
        return true;
    }

    private static boolean checkAbsolute(String text, TimeParseResult result) {
        // Strict keyword check for absolute time
        if (!(text.contains("at") || text.contains("baje") || text.contains("pm") || text.contains("am"))) {
            return false;
        }

        Matcher matcher = ABS_TIME_PATTERN.matcher(text);
        if (!matcher.find()) {
            return false;
        }

        long timeMs = parseSingleTime(matcher.group(0), text);
        if (timeMs <= 0L) {
            return false;
        }
        result.startTimeMs = timeMs;
        return true;
    }

    private static long parseSingleTime(String timeText, String context) {
        try {
            Matcher matcher = ABS_TIME_PATTERN.matcher(timeText);
            if (!matcher.find()) {
                return 0L;
            }

            int hour = Integer.parseInt(matcher.group(1));
            int minute = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
            String marker = matcher.group(3) != null ? matcher.group(3).toLowerCase() : "";

            Calendar cal = Calendar.getInstance();
            boolean isPm = marker.equals("pm") || context.contains("pm") || context.contains("night") || context.contains("shaam") || context.contains("raat");
            boolean isAm = marker.equals("am") || context.contains("am") || context.contains("morning") || context.contains("subah");

            if (hour < 12) {
                if (isPm) {
                    hour += 12;
                }
            } else if (hour == 12 && isAm) {
                hour = 0;
            }

            cal.set(Calendar.HOUR_OF_DAY, hour);
            cal.set(Calendar.MINUTE, minute);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);

            // If time is in the past, assume it's for tomorrow
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }

            return cal.getTimeInMillis();
        } catch (Exception ignore) {
            return 0L;
        }
    }
}
