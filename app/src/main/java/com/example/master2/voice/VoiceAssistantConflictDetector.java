package com.example.master2.voice;

import androidx.annotation.NonNull;

import com.example.master2.voice.model.AssistantRule;
import com.example.master2.voice.model.ConflictResult;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class VoiceAssistantConflictDetector {
    public interface ConflictCallback {
        void onResult(ConflictResult result);

        void onError(String error);
    }

    public void detect(@NonNull AssistantRule candidateRule, @NonNull ConflictCallback callback) {
        if (candidateRule.parentUserId == null || candidateRule.parentUserId.isEmpty()) {
            callback.onError("Missing parent user id");
            return;
        }
        DatabaseReference rulesRef = FirebaseDatabase.getInstance()
                .getReference("voice_assistant_rules")
                .child(candidateRule.parentUserId);

        rulesRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                ConflictResult result = new ConflictResult();
                for (DataSnapshot child : snapshot.getChildren()) {
                    AssistantRule existing = child.getValue(AssistantRule.class);
                    if (existing == null || !AssistantRule.STATUS_ACTIVE.equals(existing.status)) {
                        continue;
                    }
                    boolean childOverlap = intersects(existing.targetChildIds, candidateRule.targetChildIds);
                    boolean appOverlap = intersects(existing.apps, candidateRule.apps);
                    boolean timeOverlap = overlaps(existing.startEpochMs, existing.endEpochMs, candidateRule.startEpochMs,
                            candidateRule.endEpochMs);
                    if (childOverlap && appOverlap && timeOverlap) {
                        result.hasConflict = true;
                        result.reasons.add("Overlap with existing assistant rule: " + child.getKey());
                    }
                }
                callback.onResult(result);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                callback.onError(error.getMessage());
            }
        });
    }

    private boolean intersects(java.util.List<String> left, java.util.List<String> right) {
        for (String item : left) {
            if (right.contains(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean overlaps(long startA, long endA, long startB, long endB) {
        long aEnd = endA > 0L ? endA : startA;
        long bEnd = endB > 0L ? endB : startB;
        return startA <= bEnd && startB <= aEnd;
    }
}
