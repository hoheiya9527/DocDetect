package com.urovo.templatedetector.view;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.urovo.templatedetector.R;
import com.urovo.templatedetector.model.ContentRegion;

/**
 * 内容区域列表适配器
 */
public class ContentRegionAdapter extends ListAdapter<ContentRegion, ContentRegionAdapter.ViewHolder> {

    private OnItemClickListener itemClickListener;

    public interface OnItemClickListener {
        void onItemClick(ContentRegion region);
    }

    public ContentRegionAdapter() {
        super(new DiffUtil.ItemCallback<ContentRegion>() {
            @Override
            public boolean areItemsTheSame(@NonNull ContentRegion oldItem, @NonNull ContentRegion newItem) {
                return oldItem.getId().equals(newItem.getId());
            }

            @Override
            public boolean areContentsTheSame(@NonNull ContentRegion oldItem, @NonNull ContentRegion newItem) {
                return oldItem.isSelected() == newItem.isSelected() &&
                        oldItem.getContent().equals(newItem.getContent());
            }
        });
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.itemClickListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_content_region, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ContentRegion region = getItem(position);
        holder.bind(region);
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        private final ImageView typeIcon;
        private final TextView typeLabel;
        private final TextView contentText;
        private final ImageView selectedIndicator;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            typeIcon = itemView.findViewById(R.id.typeIcon);
            typeLabel = itemView.findViewById(R.id.typeLabel);
            contentText = itemView.findViewById(R.id.contentText);
            selectedIndicator = itemView.findViewById(R.id.selectedIndicator);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && itemClickListener != null) {
                    itemClickListener.onItemClick(getItem(position));
                }
            });
        }

        void bind(ContentRegion region) {
            // 设置类型图标
            if (region.getType() == ContentRegion.ContentType.OCR) {
                typeIcon.setImageResource(android.R.drawable.ic_menu_edit);
                typeLabel.setText("[OCR]");
            } else {
                typeIcon.setImageResource(android.R.drawable.ic_menu_gallery);
                typeLabel.setText("[" + region.getFormat() + "]");
            }

            // 设置内容
            contentText.setText(region.getContent());

            // 设置选中状态
            selectedIndicator.setVisibility(region.isSelected() ? View.VISIBLE : View.INVISIBLE);
            
            // 设置卡片背景
            itemView.setAlpha(region.isSelected() ? 1.0f : 0.7f);
        }
    }
}
