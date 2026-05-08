package com.example.master2.voice.parser;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeEntityExtractor {
    private static final Pattern REL_MIN_PATTERN = Pattern
            .compile("(\\d+)\\s*(?:minute|minutes|min|mins)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REL_HR_PATTERN = Pattern
            .compile("(\\d+)\\s*(?:hour|hours|hr|hrs|ghante|ghanta)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABS_TIME_PATTERN = Pattern
            .compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|baje|bajke)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANGE_PATTERN = Pattern.compile(
            "(?:from|se|between)?\\s*([\\d:apm\\s]+?)\\s*(?:to|se|tak|and|-)\\s*([\\d:apm\\s]+)",
            Pattern.CASE_INSENSITIVE);

    private TimeEntityExtractor() {
    }

    public static TimeParseResult extract(String normalizedText) {
        TimeParseResult result = new TimeParseResult();
        if (normalizedText == null || normalizedText.isEmpty()) {
            return result;
        }
        if (checkRange(normalizedText, result)) {
            result.hasRange = true;
            result.requiresSchedule = true;
            return result;
        }
        if (checkRelative(normalizedText, result)) {
            result.isRelative = true;
            result.requiresSchedule = true;
            return result;
        }
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
        if (result.endTimeMs <= result.startTimeMs) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(result.endTimeMs);
            cal.add(Calendar.DAY_OF_YEAR, 1);
            result.endTimeMs = cal.getTimeInMillis();
        }
        return true;
    }

    private static boolean checkRelative(String text, TimeParseResult result) {
        if (!(text.contains("baad") || text.contains("after") || text.contains("in") || text.contains("timer"))) {
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
        if (!(text.contains("at") || text.contains("baje") || text.contains("pm") || text.contains("am")
                || text.contains("subah") || text.contains("shaam") || text.contains("raat"))) {
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
            boolean isPm = marker.equals("pm") || context.contains("pm") || context.contains("raat")
                    || context.contains("night") || context.contains("shaam");
            boolean isAm = marker.equals("am") || context.contains("am") || context.contains("subah")
                    || context.contains("morning");

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

            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }
            return cal.getTimeInMillis();
        } catch (Exception ignore) {
            return 0L;
        }
    }
}
