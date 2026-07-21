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
    private long lastActivityTime;

    private String recruiterName = "";

    private String candidateName = "";
    private String appliedRole = "";
    private String tentativeSchedule = "";
    private String noticePeriod = "";
    private String mainAgenda = "";
    private String keyDiscussionPoints = "";
    private String nextSteps = "";
    private String matchingSkills = "";
    private String notMatchingSkills = "";
    private String jdLink = "";
    private String jdImagePath = "";
    private String interestRating = "";

    private static String clean(String val) {
        if (val == null || val.trim().equalsIgnoreCase("null")) {
            return "";
        }
        String trimmed = val.trim();
        String lower = trimmed.toLowerCase();
        if (lower.equals("not mentioned") || lower.equals("not mentioned.") 
                || lower.equals("not_mentioned") || lower.equals("n/a") 
                || lower.equals("none") || lower.equals("unknown")) {
            return "";
        }
        return trimmed;
    }

    // Constructor for retrieving from DB (with ID and Duration)
    public JobCall(int id, String phoneNumber, String companyName, String roundStatus, String tags, String notes, int duration, long timestamp) {
        this.id = id;
        this.phoneNumber = clean(phoneNumber);
        this.companyName = clean(companyName);
        this.roundStatus = clean(roundStatus);
        this.tags = clean(tags);
        this.notes = clean(notes);
        this.duration = duration;
        this.timestamp = timestamp;
    }

    // Constructor for creating new records (without ID, but with Duration)
    public JobCall(String phoneNumber, String companyName, String roundStatus, String tags, String notes, int duration, long timestamp) {
        this.phoneNumber = clean(phoneNumber);
        this.companyName = clean(companyName);
        this.roundStatus = clean(roundStatus);
        this.tags = clean(tags);
        this.notes = clean(notes);
        this.duration = duration;
        this.timestamp = timestamp;
    }

    public String getRecruiterName() {
        return recruiterName;
    }

    public void setRecruiterName(String recruiterName) {
        this.recruiterName = clean(recruiterName);
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
        this.phoneNumber = clean(phoneNumber);
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = clean(companyName);
    }

    public String getRoundStatus() {
        return roundStatus;
    }

    public void setRoundStatus(String roundStatus) {
        this.roundStatus = clean(roundStatus);
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = clean(tags);
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = clean(notes);
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

    public long getLastActivityTime() {
        return lastActivityTime != 0 ? lastActivityTime : timestamp;
    }

    public void setLastActivityTime(long lastActivityTime) {
        this.lastActivityTime = lastActivityTime;
    }

    public String getCandidateName() {
        return candidateName;
    }

    public void setCandidateName(String candidateName) {
        this.candidateName = clean(candidateName);
    }

    public String getAppliedRole() {
        return appliedRole;
    }

    public void setAppliedRole(String appliedRole) {
        this.appliedRole = clean(appliedRole);
    }

    public String getTentativeSchedule() {
        return tentativeSchedule;
    }

    public void setTentativeSchedule(String tentativeSchedule) {
        this.tentativeSchedule = clean(tentativeSchedule);
    }

    public String getNoticePeriod() {
        return noticePeriod;
    }

    public void setNoticePeriod(String noticePeriod) {
        this.noticePeriod = clean(noticePeriod);
    }

    public String getMainAgenda() {
        return mainAgenda;
    }

    public void setMainAgenda(String mainAgenda) {
        this.mainAgenda = clean(mainAgenda);
    }

    public String getKeyDiscussionPoints() {
        return keyDiscussionPoints;
    }

    public void setKeyDiscussionPoints(String keyDiscussionPoints) {
        this.keyDiscussionPoints = clean(keyDiscussionPoints);
    }

    public String getNextSteps() {
        return nextSteps;
    }

    public void setNextSteps(String nextSteps) {
        this.nextSteps = clean(nextSteps);
    }

    public String getMatchingSkills() {
        return matchingSkills;
    }

    public void setMatchingSkills(String matchingSkills) {
        this.matchingSkills = clean(matchingSkills);
    }

    public String getNotMatchingSkills() {
        return notMatchingSkills;
    }

    public void setNotMatchingSkills(String notMatchingSkills) {
        this.notMatchingSkills = clean(notMatchingSkills);
    }

    public String getJdLink() {
        return jdLink;
    }

    public void setJdLink(String jdLink) {
        this.jdLink = clean(jdLink);
    }

    public String getJdImagePath() {
        return jdImagePath;
    }

    public void setJdImagePath(String jdImagePath) {
        this.jdImagePath = clean(jdImagePath);
    }

    public String getInterestRating() {
        return interestRating != null ? interestRating : "";
    }

    public void setInterestRating(String interestRating) {
        this.interestRating = clean(interestRating);
    }
}
