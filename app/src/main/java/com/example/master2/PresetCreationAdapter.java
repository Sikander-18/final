package com.example.master2;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PresetCreationAdapter extends RecyclerView.Adapter<PresetCreationAdapter.ViewHolder> {
    private Context context;
    private List<AppInfo> appList;
    private Set<String> selectedPackages;
    private OnSelectionChangedListener selectionListener;

    public interface OnSelectionChangedListener {
        void onSelectionChanged();
    }

    public PresetCreationAdapter(Context context, List<AppInfo> appList) {
        this.context = context;
        this.appList = appList;
        this.selectedPackages = new HashSet<>();
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.selectionListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_app_preset, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppInfo app = appList.get(position);

        holder.tvAppName.setText(app.name);
        holder.tvPackageName.setText(app.packageName);

        // Handle icon display
        if (app.icon != null) {
            holder.ivAppIcon.setImageDrawable(app.icon);
        } else if (app.iconBase64 != null && !app.iconBase64.isEmpty()) {
            try {
                byte[] decodedBytes = Base64.decode(app.iconBase64, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                holder.ivAppIcon.setImageBitmap(bitmap);
            } catch (Exception e) {
                holder.ivAppIcon.setImageResource(R.drawable.ic_launcher_foreground);
            }
        } else {
            holder.ivAppIcon.setImageResource(R.drawable.ic_launcher_foreground);
        }

        // Set checkbox state
        boolean isSelected = selectedPackages.contains(app.packageName);
        holder.cbBlock.setChecked(isSelected);

        // Handle checkbox changes
        holder.cbBlock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                selectedPackages.add(app.packageName);
            } else {
                selectedPackages.remove(app.packageName);
            }

            if (selectionListener != null) {
                selectionListener.onSelectionChanged();
            }
        });

        // Handle item click to toggle checkbox
        holder.itemView.setOnClickListener(v -> {
            holder.cbBlock.setChecked(!holder.cbBlock.isChecked());
        });
    }

    @Override
    public int getItemCount() {
        return appList.size();
    }

    public int getSelectedCount() {
        return selectedPackages.size();
    }

    public List<String> getSelectedAppPackages() {
        return new ArrayList<>(selectedPackages);
    }

    public void selectAll() {
        selectedPackages.clear();
        for (AppInfo app : appList) {
            selectedPackages.add(app.packageName);
        }
        notifyDataSetChanged();
    }

    public void deselectAll() {
        selectedPackages.clear();
        notifyDataSetChanged();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivAppIcon;
        TextView tvAppName, tvPackageName;
        CheckBox cbBlock;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAppIcon = itemView.findViewById(R.id.ivAppIcon);
            tvAppName = itemView.findViewById(R.id.tvAppName);
            tvPackageName = itemView.findViewById(R.id.tvPackageName);
            cbBlock = itemView.findViewById(R.id.cbBlock);
        }
    }
}