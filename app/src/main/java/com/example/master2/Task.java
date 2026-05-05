package com.example.master2;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class Task implements Serializable {
    public String title;
    public String time;
    public String description;
    public boolean isCompleted;
    public String date;
    public String createdDate;
    public boolean isToday;
    public Map<String, Boolean> dailyCompletionMap;

    public Task() {
        // Default constructor for Firebase
        this.dailyCompletionMap = new HashMap<>();
        this.isToday = false;
    }

    public Task(String title, String time, String description, boolean isCompleted, String date) {
        this.title = title;
        this.time = time;
        this.description = description;
        this.isCompleted = isCompleted;
        this.date = date;
        this.createdDate = date;
        this.isToday = false;
        this.dailyCompletionMap = new HashMap<>();
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isCompleted() {
        return isCompleted;
    }

    public boolean isCompleted(String date) {
        if (dailyCompletionMap != null && dailyCompletionMap.containsKey(date)) {
            return dailyCompletionMap.get(date);
        }
        return false;
    }

    public void setCompleted(boolean completed) {
        isCompleted = completed;
    }

    public void setCompleted(String date, boolean completed) {
        if (dailyCompletionMap == null) {
            dailyCompletionMap = new HashMap<>();
        }
        dailyCompletionMap.put(date, completed);
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }
}
