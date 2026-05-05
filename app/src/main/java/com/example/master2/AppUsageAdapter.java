package com.example.master2;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.master2.models.AppUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AppUsageAdapter extends RecyclerView.Adapter<AppUsageAdapter.UsageViewHolder> {

    private List<AppUsage> data = new ArrayList<>();

    public void setData(List<AppUsage> newData) {
        data = newData != null ? newData : new ArrayList<>();
        notifyDataSetChanged();
    }

    // Add helper to derive a readable name from the package string
    private String deriveFriendlyName(String packageName){
        if(packageName == null) return "";
        int idx = packageName.lastIndexOf('.');
        String raw = idx >= 0 ? packageName.substring(idx+1) : packageName;
        if(raw.isEmpty()) return packageName;
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    @NonNull
    @Override
    public UsageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app_usage, parent, false);
        return new UsageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull UsageViewHolder holder, int position) {
        AppUsage item = data.get(position);

        // Determine display name
        String displayName = item.getAppName();
        if(displayName == null || displayName.trim().isEmpty() || displayName.equals(item.getPackageName()) || displayName.contains(".")){
            displayName = deriveFriendlyName(item.getPackageName());
        }
        holder.tvName.setText(displayName);

        holder.tvTime.setText(formatDuration(item.getUsageTime()));

        // Try to retrieve icon from package manager (fallback to default)
        try {
            Drawable icon = holder.itemView.getContext().getPackageManager()
                    .getApplicationIcon(item.getPackageName());
            holder.icon.setImageDrawable(icon);
        } catch (Exception e) {
            holder.icon.setImageResource(R.drawable.ic_launcher_foreground);
        }
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    private String formatDuration(long millis) {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        long hours = minutes / 60;
        long remMinutes = minutes % 60;
        if (hours > 0) return hours + " hr " + remMinutes + " min";
        return remMinutes + " min";
    }

    static class UsageViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView tvName, tvTime;
        ImageView timerIcon;

        UsageViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.ivIcon);
            tvName = itemView.findViewById(R.id.tvAppName);
            tvTime = itemView.findViewById(R.id.tvUsageTime);
            timerIcon = itemView.findViewById(R.id.ivTimer);
        }
    }
} 