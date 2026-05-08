package com.example.master2.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.master2.R;
import com.example.master2.voice.model.AssistantChatMessage;
import com.example.master2.voice.model.VoiceCommandIntent;

import java.util.List;

/**
 * RecyclerView adapter for the voice assistant chat.
 * Shows user messages, bot messages, and action confirmation cards.
 */
public class AssistantChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_USER = 0;
    private static final int TYPE_BOT = 1;
    private static final int TYPE_ACTION = 2;

    private final List<AssistantChatMessage> messages;
    private final ActionCallback callback;

    public interface ActionCallback {
        void onConfirm(VoiceCommandIntent intent);
        void onCancel();
    }

    public AssistantChatAdapter(List<AssistantChatMessage> messages, ActionCallback callback) {
        this.messages = messages;
        this.callback = callback;
    }

    @Override
    public int getItemViewType(int position) {
        AssistantChatMessage msg = messages.get(position);
        if (msg.isActionCard()) return TYPE_ACTION;
        return msg.getSender() == AssistantChatMessage.Sender.USER ? TYPE_USER : TYPE_BOT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_USER) {
            return new UserVH(inflater.inflate(R.layout.item_assistant_message_user, parent, false));
        } else if (viewType == TYPE_BOT) {
            return new BotVH(inflater.inflate(R.layout.item_assistant_message_bot, parent, false));
        } else {
            return new ActionVH(inflater.inflate(R.layout.item_assistant_action_card, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        AssistantChatMessage msg = messages.get(position);

        if (holder instanceof UserVH) {
            ((UserVH) holder).tvMessage.setText(msg.getText());

        } else if (holder instanceof BotVH) {
            ((BotVH) holder).tvMessage.setText(msg.getText());

        } else if (holder instanceof ActionVH) {
            ActionVH vh = (ActionVH) holder;
            String title = (msg.getActionType() != null ? capitalize(msg.getActionType()) : "Action")
                    + " " + (msg.getAppName() != null ? msg.getAppName() : "App");
            vh.tvTitle.setText(title);
            vh.tvDetails.setText(msg.getScheduleLabel() != null ? msg.getScheduleLabel() : "Will execute immediately");

            vh.btnConfirm.setOnClickListener(v -> {
                if (callback != null) callback.onConfirm(msg.getCommandIntent());
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    messages.remove(pos);
                    notifyItemRemoved(pos);
                }
            });
            vh.btnCancel.setOnClickListener(v -> {
                if (callback != null) callback.onCancel();
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    messages.remove(pos);
                    notifyItemRemoved(pos);
                }
            });
        }
    }

    @Override
    public int getItemCount() { return messages.size(); }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    static class UserVH extends RecyclerView.ViewHolder {
        TextView tvMessage;
        UserVH(View v) { super(v); tvMessage = v.findViewById(R.id.tvUserMessage); }
    }
    static class BotVH extends RecyclerView.ViewHolder {
        TextView tvMessage;
        BotVH(View v) { super(v); tvMessage = v.findViewById(R.id.tvBotMessage); }
    }
    static class ActionVH extends RecyclerView.ViewHolder {
        TextView tvTitle, tvDetails;
        Button btnConfirm, btnCancel;
        ActionVH(View v) {
            super(v);
            tvTitle = v.findViewById(R.id.tvActionTitle);
            tvDetails = v.findViewById(R.id.tvActionDetails);
            btnConfirm = v.findViewById(R.id.btnConfirm);
            btnCancel = v.findViewById(R.id.btnCancel);
        }
    }
}
