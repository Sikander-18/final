package com.example.master2.adapters;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.master2.R;
import com.example.master2.models.SUsageAppInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RecyclerView adapter for displaying app usage items.
 * Loads app icons dynamically from packageName.
 */
public class SUsageAppAdapter extends RecyclerView.Adapter<SUsageAppAdapter.ViewHolder> {

    private List<SUsageAppInfo> appUsageList;
    private OnItemClickListener listener;
    private PackageManager packageManager;
    private boolean isRemoteMode = false; // True when viewing remote child's data on parent

    public interface OnItemClickListener {
        void onItemClick(SUsageAppInfo appUsageInfo);
    }

    public SUsageAppAdapter() {
        this.appUsageList = new ArrayList<>();
    }

    public void setPackageManager(PackageManager pm) {
        this.packageManager = pm;
    }

    /**
     * Set remote mode - when true, don't try to load app icons
     * Use this on parent device when viewing child's apps
     */
    public void setRemoteMode(boolean isRemote) {
        this.isRemoteMode = isRemote;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void updateData(List<SUsageAppInfo> newData) {
        this.appUsageList = newData != null ? new ArrayList<>(newData) : new ArrayList<>();
        // Sort by usage time descending
        Collections.sort(this.appUsageList, (a, b) -> Long.compare(b.getUsageTimeMillis(), a.getUsageTimeMillis()));
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_susage_app, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SUsageAppInfo appUsage = appUsageList.get(position);
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
        private final TextView categoryText;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            appIcon = itemView.findViewById(R.id.appIcon);
            appName = itemView.findViewById(R.id.appName);
            usageTime = itemView.findViewById(R.id.usageTime);
            categoryText = itemView.findViewById(R.id.categoryText);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    listener.onItemClick(appUsageList.get(position));
                }
            });
        }

        void bind(SUsageAppInfo appUsage) {
            appName.setText(appUsage.getAppName());
            usageTime.setText(appUsage.getFormattedUsageTime());

            if (categoryText != null) {
                categoryText.setText(appUsage.getCategory());
            }

            // Try to load icon from Base64 first (for remote/parent viewing)
            if (appUsage.getIconBase64() != null && !appUsage.getIconBase64().isEmpty()) {
                try {
                    byte[] decodedBytes = android.util.Base64.decode(appUsage.getIconBase64(),
                            android.util.Base64.NO_WRAP);
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0,
                            decodedBytes.length);
                    appIcon.setImageBitmap(bitmap);
                    return; // Icon loaded successfully
                } catch (Exception e) {
                    // Fall through to try PackageManager
                }
            }

            // Load app icon from package name (for local child viewing)
            // Skip icon loading in remote mode (parent viewing child's apps)
            if (!isRemoteMode && packageManager != null) {
                try {
                    Drawable icon = packageManager.getApplicationIcon(appUsage.getPackageName());
                    appIcon.setImageDrawable(icon);
                } catch (PackageManager.NameNotFoundException e) {
                    appIcon.setImageResource(R.mipmap.ic_launcher);
                }
            } else {
                // Remote mode or no package manager - use default icon
                appIcon.setImageResource(R.mipmap.ic_launcher);
            }
        }
    }
}
