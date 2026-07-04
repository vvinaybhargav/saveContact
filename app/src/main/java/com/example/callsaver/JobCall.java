package com.example.callsaver;

public class JobCall {
    private int id;
    private String phoneNumber;
    private String companyName;
    private String roundStatus;
    private String tags;
    private String notes;
    private int duration; // Call duration in seconds
    private long timestamp;

    // Constructor for retrieving from DB (with ID and Duration)
    public JobCall(int id, String phoneNumber, String companyName, String roundStatus, String tags, String notes, int duration, long timestamp) {
        this.id = id;
        this.phoneNumber = phoneNumber;
        this.companyName = companyName;
        this.roundStatus = roundStatus;
        this.tags = tags;
        this.notes = notes;
        this.duration = duration;
        this.timestamp = timestamp;
    }

    // Constructor for creating new records (without ID, but with Duration)
    public JobCall(String phoneNumber, String companyName, String roundStatus, String tags, String notes, int duration, long timestamp) {
        this.phoneNumber = phoneNumber;
        this.companyName = companyName;
        this.roundStatus = roundStatus;
        this.tags = tags;
        this.notes = notes;
        this.duration = duration;
        this.timestamp = timestamp;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getRoundStatus() {
        return roundStatus;
    }

    public void setRoundStatus(String roundStatus) {
        this.roundStatus = roundStatus;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
