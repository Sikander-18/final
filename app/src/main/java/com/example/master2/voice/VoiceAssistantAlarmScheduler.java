package com.example.master2.voice;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.example.master2.voice.model.AssistantRule;

public final class VoiceAssistantAlarmScheduler {
    public static final String EXTRA_PARENT_USER_ID = "extra_parent_user_id";
    public static final String EXTRA_RULE_ID = "extra_rule_id";
    public static final String EXTRA_PHASE = "extra_phase";
    public static final String PHASE_START = "start";
    public static final String PHASE_END = "end";

    private VoiceAssistantAlarmScheduler() {
    }

    public static void scheduleRule(Context context, AssistantRule rule, String phase, long triggerAtMillis) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null || triggerAtMillis <= 0L) {
            return;
        }

        Intent intent = new Intent(context, VoiceAssistantAlarmReceiver.class);
        intent.putExtra(EXTRA_PARENT_USER_ID, rule.parentUserId);
        intent.putExtra(EXTRA_RULE_ID, rule.ruleId);
        intent.putExtra(EXTRA_PHASE, phase);

        int requestCode = (rule.ruleId + ":" + phase).hashCode();
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        }
    }
}
