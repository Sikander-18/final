package com.example.master2.voice.parser;

public class TimeParseResult {
    public long startTimeMs = 0L;
    public long endTimeMs = 0L;
    public boolean hasRange = false;
    public boolean isRelative = false;
    public boolean isAbsolute = false;
    public boolean requiresSchedule = false;
}
