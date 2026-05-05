package com.example.master2.models;

public class User {
    private String username;
    private String email;
    private String phone;
    private String userType;
    private String uid;
    private long createdAt;
    private long lastUpdated;

    public User() {
        // Default constructor required for calls to DataSnapshot.getValue(User.class)
        this.createdAt = System.currentTimeMillis();
        this.lastUpdated = System.currentTimeMillis();
    }

    public User(String username, String email, String phone, String userType, String uid) {
        this.username = username;
        this.email = email;
        this.phone = phone;
        this.userType = userType;
        this.uid = uid;
        this.createdAt = System.currentTimeMillis();
        this.lastUpdated = System.currentTimeMillis();
    }

    // Getters and Setters
    public String getUsername() { return username; }
    public void setUsername(String username) { 
        this.username = username; 
        this.lastUpdated = System.currentTimeMillis();
    }

    public String getEmail() { return email; }
    public void setEmail(String email) { 
        this.email = email; 
        this.lastUpdated = System.currentTimeMillis();
    }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { 
        this.phone = phone; 
        this.lastUpdated = System.currentTimeMillis();
    }

    public String getUserType() { return userType; }
    public void setUserType(String userType) { 
        this.userType = userType; 
        this.lastUpdated = System.currentTimeMillis();
    }

    public String getUid() { return uid; }
    public void setUid(String uid) { 
        this.uid = uid; 
        this.lastUpdated = System.currentTimeMillis();
    }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(long lastUpdated) { this.lastUpdated = lastUpdated; }

    @Override
    public String toString() {
        return "User{" +
                "username='" + username + '\'' +
                ", email='" + email + '\'' +
                ", phone='" + phone + '\'' +
                ", userType='" + userType + '\'' +
                ", uid='" + uid + '\'' +
                ", createdAt=" + createdAt +
                ", lastUpdated=" + lastUpdated +
                '}';
    }
}
