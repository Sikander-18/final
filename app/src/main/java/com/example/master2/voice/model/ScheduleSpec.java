package com.example.master2.voice.model;

import java.util.Calendar;

public class ScheduleSpec {
    public static final String TYPE_IMMEDIATE = "immediate";
    public static final String TYPE_AT_TIME = "at_time";
    public static final String TYPE_AFTER_DURATION = "after_duration";
    public static final String TYPE_TIME_RANGE = "time_range";

    public String scheduleType = TYPE_IMMEDIATE;
    public long startEpochMs = 0L;
    public long endEpochMs = 0L;
    public long delayMs = 0L;
    public boolean crossesMidnight = false;

    public boolean isValid() {
        if (TYPE_IMMEDIATE.equals(scheduleType)) {
            return true;
        }
        if (TYPE_AFTER_DURATION.equals(scheduleType)) {
            return delayMs > 0L;
        }
        if (TYPE_AT_TIME.equals(scheduleType)) {
            return startEpochMs > 0L;
        }
        if (TYPE_TIME_RANGE.equals(scheduleType)) {
            return startEpochMs > 0L && endEpochMs > 0L && endEpochMs != startEpochMs;
        }
        return false;
    }

    public static long resolveTodayTime(int hour24, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.set(Calendar.HOUR_OF_DAY, hour24);
        calendar.set(Calendar.MINUTE, minute);
        return calendar.getTimeInMillis();
    }
}
