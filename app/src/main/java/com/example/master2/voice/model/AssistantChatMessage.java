package com.example.master2.voice.model;

/**
 * Represents a single message in the voice assistant chat UI.
 */
public class AssistantChatMessage {
    public enum Sender { USER, ASSISTANT }

    private final Sender sender;
    private final String text;
    private final long timestamp;

    // For action confirmation cards
    private final boolean isActionCard;
    private final String actionType;      // "block" / "unblock"
    private final String appName;
    private final String packageName;
    private final String scheduleLabel;   // "Immediately" / "Scheduled for 10:00 PM"
    private final VoiceCommandIntent commandIntent;

    /** Plain text message */
    public AssistantChatMessage(Sender sender, String text) {
        this.sender = sender;
        this.text = text;
        this.timestamp = System.currentTimeMillis();
        this.isActionCard = false;
        this.actionType = null;
        this.appName = null;
        this.packageName = null;
        this.scheduleLabel = null;
        this.commandIntent = null;
    }

    /** Confirmation card message */
    public AssistantChatMessage(Sender sender, String actionType, String appName,
                                String packageName, String scheduleLabel,
                                VoiceCommandIntent commandIntent) {
        this.sender = sender;
        this.text = "";
        this.timestamp = System.currentTimeMillis();
        this.isActionCard = true;
        this.actionType = actionType;
        this.appName = appName;
        this.packageName = packageName;
        this.scheduleLabel = scheduleLabel;
        this.commandIntent = commandIntent;
    }

    public Sender getSender()              { return sender; }
    public String getText()                { return text; }
    public long getTimestamp()             { return timestamp; }
    public boolean isActionCard()          { return isActionCard; }
    public String getActionType()          { return actionType; }
    public String getAppName()             { return appName; }
    public String getPackageName()         { return packageName; }
    public String getScheduleLabel()       { return scheduleLabel; }
    public VoiceCommandIntent getCommandIntent() { return commandIntent; }
}
