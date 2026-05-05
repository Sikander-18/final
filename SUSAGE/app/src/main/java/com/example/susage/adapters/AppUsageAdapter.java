package com.example.susage.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.susage.R;
import com.example.susage.models.AppUsageInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for displaying app usage items in a RecyclerView.
 */
public class AppUsageAdapter extends RecyclerView.Adapter<AppUsageAdapter.ViewHolder> {

    private List<AppUsageInfo> appUsageList;
    private OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(AppUsageInfo appUsageInfo);
        void onInfoClick(AppUsageInfo appUsageInfo);
    }

    public AppUsageAdapter() {
        this.appUsageList = new ArrayList<>();
    }

    public AppUsageAdapter(List<AppUsageInfo> appUsageList) {
        this.appUsageList = appUsageList != null ? appUsageList : new ArrayList<>();
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void updateData(List<AppUsageInfo> newData) {
        this.appUsageList = newData != null ? newData : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app_usage, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppUsageInfo appUsage = appUsageList.get(position);
        holder.bind(appUsage);
    }

    @Override
    public int getItemCount() {
        return appUsageList.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView appIcon;
        private final TextView appName;
        private final TextView usageTime;
        private final ImageView infoIcon;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            appIcon = itemView.findViewById(R.id.appIcon);
            appName = itemView.findViewById(R.id.appName);
            usageTime = itemView.findViewById(R.id.usageTime);
            infoIcon = itemView.findViewById(R.id.infoIcon);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    listener.onItemClick(appUsageList.get(position));
                }
            });

            infoIcon.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    listener.onInfoClick(appUsageList.get(position));
                }
            });
        }

        void bind(AppUsageInfo appUsage) {
            appName.setText(appUsage.getAppName());
            usageTime.setText(appUsage.getFormattedUsageTime());
            
            if (appUsage.getAppIcon() != null) {
                appIcon.setImageDrawable(appUsage.getAppIcon());
            } else {
                appIcon.setImageResource(R.mipmap.ic_launcher);
            }
        }
    }
}
