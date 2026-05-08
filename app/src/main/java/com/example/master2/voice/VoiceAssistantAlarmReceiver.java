package com.example.master2.voice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.master2.BlockCommand;
import com.example.master2.voice.model.AssistantRule;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

public class VoiceAssistantAlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "VoiceAssistantAlarmRx";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String parentUserId = intent.getStringExtra(VoiceAssistantAlarmScheduler.EXTRA_PARENT_USER_ID);
        String ruleId = intent.getStringExtra(VoiceAssistantAlarmScheduler.EXTRA_RULE_ID);
        String phase = intent.getStringExtra(VoiceAssistantAlarmScheduler.EXTRA_PHASE);
        if (TextUtils.isEmpty(parentUserId) || TextUtils.isEmpty(ruleId) || TextUtils.isEmpty(phase)) {
            return;
        }

        DatabaseReference ruleRef = FirebaseDatabase.getInstance()
                .getReference("voice_assistant_rules")
                .child(parentUserId)
                .child(ruleId);

        ruleRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                AssistantRule rule = snapshot.getValue(AssistantRule.class);
                if (rule == null || !AssistantRule.STATUS_ACTIVE.equals(rule.status)) {
                    return;
                }

                boolean shouldBlock;
                if (VoiceAssistantAlarmScheduler.PHASE_START.equals(phase)) {
                    shouldBlock = "block".equals(rule.action);
                } else {
                    shouldBlock = !"block".equals(rule.action);
                }

                dispatchCommands(parentUserId, rule, shouldBlock);

                Map<String, Object> updates = new HashMap<>();
                if (VoiceAssistantAlarmScheduler.PHASE_START.equals(phase)) {
                    updates.put("executedStart", true);
                    if ("time_range".equals(rule.scheduleType) && rule.endEpochMs > 0L) {
                        VoiceAssistantAlarmScheduler.scheduleRule(context, rule, VoiceAssistantAlarmScheduler.PHASE_END,
                                rule.endEpochMs);
                    } else {
                        updates.put("status", AssistantRule.STATUS_COMPLETED);
                    }
                } else {
                    updates.put("executedEnd", true);
                    updates.put("status", AssistantRule.STATUS_COMPLETED);
                }
                ruleRef.updateChildren(updates);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Failed to fetch rule: " + error.getMessage());
            }
        });
    }

    private void dispatchCommands(String parentUserId, AssistantRule rule, boolean block) {
        DatabaseReference root = FirebaseDatabase.getInstance().getReference("block_commands");
        for (String childId : rule.targetChildIds) {
            for (String packageName : rule.apps) {
                BlockCommand command = new BlockCommand(
                        childId,
                        parentUserId,
                        packageName,
                        packageName,
                        block,
                        "voice_assistant",
                        rule.ruleId);
                root.child(childId).child(command.commandId).setValue(command);
            }
        }
    }
}
