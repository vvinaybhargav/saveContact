package com.example.callsaver;

import java.io.Serializable;

public class EmailMessage implements Serializable {
    private long id;
    private long jobCallId;
    private String gmailMessageId;
    private String sender;
    private String recipient;
    private String subject;
    private String snippet;
    private String body;
    private long receivedTimestamp;
    private boolean isRead;

    public EmailMessage() {
    }

    public EmailMessage(long id, long jobCallId, String gmailMessageId, String sender, String recipient, String subject, String snippet, String body, long receivedTimestamp) {
        this.id = id;
        this.jobCallId = jobCallId;
        this.gmailMessageId = gmailMessageId;
        this.sender = sender;
        this.recipient = recipient;
        this.subject = subject;
        this.snippet = snippet;
        this.body = body;
        this.receivedTimestamp = receivedTimestamp;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getJobCallId() {
        return jobCallId;
    }

    public void setJobCallId(long jobCallId) {
        this.jobCallId = jobCallId;
    }

    public String getGmailMessageId() {
        return gmailMessageId;
    }

    public void setGmailMessageId(String gmailMessageId) {
        this.gmailMessageId = gmailMessageId;
    }

    public String getSender() {
        return sender != null ? sender : "";
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getRecipient() {
        return recipient != null ? recipient : "";
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getSubject() {
        return subject != null && !subject.trim().isEmpty() ? subject : "(No Subject)";
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getSnippet() {
        return snippet != null ? snippet : "";
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public String getBody() {
        return body != null ? body : "";
    }

    public void setBody(String body) {
        this.body = body;
    }

    public long getReceivedTimestamp() {
        return receivedTimestamp;
    }

    public void setReceivedTimestamp(long receivedTimestamp) {
        this.receivedTimestamp = receivedTimestamp;
    }

    public boolean isRead() {
        return isRead;
    }

    public void setRead(boolean read) {
        isRead = read;
    }

    public String getSenderDisplayName() {
        if (sender == null || sender.isEmpty()) return "Unknown Sender";
        if (sender.contains("<")) {
            String name = sender.substring(0, sender.indexOf("<")).trim();
            if (!name.isEmpty()) {
                if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) {
                    name = name.substring(1, name.length() - 1);
                }
                return name;
            }
        }
        return sender;
    }

    public String getSenderEmail() {
        if (sender == null || sender.isEmpty()) return "";
        if (sender.contains("<") && sender.contains(">")) {
            return sender.substring(sender.indexOf("<") + 1, sender.indexOf(">")).trim();
        }
        return sender;
    }
}
