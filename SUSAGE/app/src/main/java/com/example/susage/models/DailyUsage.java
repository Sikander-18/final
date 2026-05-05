package com.example.susage.models;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Model class representing daily usage aggregation.
 */
public class DailyUsage {
    private Calendar date;
    private long totalScreenTimeMillis;
    private long communicationTimeMillis;
    private long entertainmentTimeMillis;
    private long gamesTimeMillis;
    private long otherTimeMillis;
    private List<AppUsageInfo> appUsageList;

    public DailyUsage(Calendar date) {
        this.date = date;
        this.totalScreenTimeMillis = 0;
        this.communicationTimeMillis = 0;
        this.entertainmentTimeMillis = 0;
        this.gamesTimeMillis = 0;
        this.otherTimeMillis = 0;
        this.appUsageList = new ArrayList<>();
    }

    public Calendar getDate() {
        return date;
    }

    public void setDate(Calendar date) {
        this.date = date;
    }

    public long getTotalScreenTimeMillis() {
        return totalScreenTimeMillis;
    }

    public void setTotalScreenTimeMillis(long totalScreenTimeMillis) {
        this.totalScreenTimeMillis = totalScreenTimeMillis;
    }

    public long getCommunicationTimeMillis() {
        return communicationTimeMillis;
    }

    public void setCommunicationTimeMillis(long communicationTimeMillis) {
        this.communicationTimeMillis = communicationTimeMillis;
    }

    public long getEntertainmentTimeMillis() {
        return entertainmentTimeMillis;
    }

    public void setEntertainmentTimeMillis(long entertainmentTimeMillis) {
        this.entertainmentTimeMillis = entertainmentTimeMillis;
    }

    public long getGamesTimeMillis() {
        return gamesTimeMillis;
    }

    public void setGamesTimeMillis(long gamesTimeMillis) {
        this.gamesTimeMillis = gamesTimeMillis;
    }

    public long getOtherTimeMillis() {
        return otherTimeMillis;
    }

    public void setOtherTimeMillis(long otherTimeMillis) {
        this.otherTimeMillis = otherTimeMillis;
    }

    public List<AppUsageInfo> getAppUsageList() {
        return appUsageList;
    }

    public void setAppUsageList(List<AppUsageInfo> appUsageList) {
        this.appUsageList = appUsageList;
    }

    public void addAppUsage(AppUsageInfo appUsage) {
        this.appUsageList.add(appUsage);
        this.totalScreenTimeMillis += appUsage.getUsageTimeMillis();
    }

    /**
     * Returns formatted total screen time (e.g., "1 hrs 15 min")
     */
    public String getFormattedTotalTime() {
        long totalMinutes = totalScreenTimeMillis / (1000 * 60);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        if (hours > 0) {
            return hours + " hrs " + minutes + " min";
        }
        return minutes + " min";
    }

    /**
     * Returns short day name (e.g., "Sun", "Mon")
     */
    public String getShortDayName() {
        String[] days = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        return days[date.get(Calendar.DAY_OF_WEEK) - 1];
    }

    /**
     * Returns formatted date (e.g., "Today, 18 Oct")
     */
    public String getFormattedDate() {
        Calendar today = Calendar.getInstance();
        
        if (isSameDay(date, today)) {
            return "Today, " + getMonthDay();
        }
        
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        
        if (isSameDay(date, yesterday)) {
            return "Yesterday, " + getMonthDay();
        }
        
        return getShortDayName() + ", " + getMonthDay();
    }

    private String getMonthDay() {
        String[] months = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", 
                          "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        return date.get(Calendar.DAY_OF_MONTH) + " " + months[date.get(Calendar.MONTH)];
    }

    private boolean isSameDay(Calendar cal1, Calendar cal2) {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * Returns screen time in hours for chart display
     */
    public float getScreenTimeHours() {
        return totalScreenTimeMillis / (1000f * 60f * 60f);
    }

    /**
     * Returns usage percentage (for progress bar, assuming 8h daily target)
     */
    public int getUsagePercentage() {
        long targetMillis = 8 * 60 * 60 * 1000; // 8 hours target
        int percentage = (int) ((totalScreenTimeMillis * 100) / targetMillis);
        return Math.min(percentage, 100);
    }
}
