package com.example.master2;

public class BlockCommand {
    public String commandId;
    public String targetDeviceId;
    public String controllerDeviceId;
    public String packageName;
    public String appName;
    public boolean blockStatus;
    public long timestamp;
    public boolean executed;
    public String source;
    public String sourceRuleId;

    public BlockCommand() {
        // Default constructor for Firebase
    }

    public BlockCommand(String targetDeviceId, String controllerDeviceId,
                        String packageName, String appName, boolean blockStatus) {
        this.commandId = "cmd_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 1000);
        this.targetDeviceId = targetDeviceId;
        this.controllerDeviceId = controllerDeviceId;
        this.packageName = packageName;
        this.appName = appName;
        this.blockStatus = blockStatus;
        this.timestamp = System.currentTimeMillis();
        this.executed = false;
        this.source = "manual";
        this.sourceRuleId = "";
    }

    public BlockCommand(String targetDeviceId, String controllerDeviceId,
                        String packageName, String appName, boolean blockStatus,
                        String source, String sourceRuleId) {
        this(targetDeviceId, controllerDeviceId, packageName, appName, blockStatus);
        this.source = source;
        this.sourceRuleId = sourceRuleId == null ? "" : sourceRuleId;
    }
}