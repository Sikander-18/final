package com.example.master2;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for displaying app installation/uninstallation events
 */
public class AppStatusAdapter extends RecyclerView.Adapter<AppStatusAdapter.ViewHolder> {

    private Context context;
    private List<AppStatusEvent> events;
    private PackageManager packageManager;

    public AppStatusAdapter(Context context, List<AppStatusEvent> events) {
        this.context = context;
        this.events = events;
        this.packageManager = context.getPackageManager();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_app_status, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppStatusEvent event = events.get(position);

        // Set app name
        holder.tvAppName.setText(event.getAppName());

        // Set package name
        holder.tvPackageName.setText(event.getPackageName());

        // Set timestamp
        holder.tvTimestamp.setText(formatTimestamp(event.getTimestamp()));

        // Set action badge
        if (event.isInstalled()) {
            holder.tvAction.setText("INSTALLED");
            holder.tvAction.setBackgroundResource(R.drawable.bg_badge_success);
        } else {
            holder.tvAction.setText("UNINSTALLED");
            holder.tvAction.setBackgroundResource(R.drawable.bg_badge_error);
        }

        // Load app icon
        loadAppIcon(holder.imgAppIcon, event.getPackageName(), event.isInstalled());
    }

    @Override
    public int getItemCount() {
        return events != null ? events.size() : 0;
    }

    /**
     * Load app icon from package manager
     */
    private void loadAppIcon(ImageView imageView, String packageName, boolean isInstalled) {
        try {
            if (isInstalled) {
                // Try to get icon from package manager (if still installed)
                Drawable icon = packageManager.getApplicationIcon(packageName);
                imageView.setImageDrawable(icon);
            } else {
                // App is uninstalled, show default icon
                imageView.setImageResource(R.drawable.ic_app);
            }
        } catch (PackageManager.NameNotFoundException e) {
            // App not found or uninstalled, use default icon
            imageView.setImageResource(R.drawable.ic_app);
        }
    }

    /**
     * Format timestamp to readable format
     */
    private String formatTimestamp(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy - hh:mm a", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView imgAppIcon;
        TextView tvAppName;
        TextView tvPackageName;
        TextView tvTimestamp;
        TextView tvAction;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            imgAppIcon = itemView.findViewById(R.id.imgAppIcon);
            tvAppName = itemView.findViewById(R.id.tvAppName);
            tvPackageName = itemView.findViewById(R.id.tvPackageName);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
            tvAction = itemView.findViewById(R.id.tvAction);
        }
    }
}
