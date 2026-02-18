package net.devemperor.dictate.clipboard;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import net.devemperor.dictate.R;

import java.util.List;

public class ClipboardHistoryAdapter extends RecyclerView.Adapter<ClipboardHistoryAdapter.ViewHolder> {

    private final List<ClipboardHistoryModel> data;
    private final ClipboardCallback callback;
    private final int cardColor;
    private final int textColor;

    public interface ClipboardCallback {
        void onItemClicked(ClipboardHistoryModel model);
        void onItemLongClicked(ClipboardHistoryModel model, int position, View anchor);
    }

    public ClipboardHistoryAdapter(List<ClipboardHistoryModel> data, ClipboardCallback callback, int cardColor, int textColor) {
        this.data = data;
        this.callback = callback;
        this.cardColor = cardColor;
        this.textColor = textColor;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView clipTextTv;
        final ImageView pinIv;
        final MaterialCardView cardView;

        public ViewHolder(View itemView) {
            super(itemView);
            clipTextTv = itemView.findViewById(R.id.clip_item_text_tv);
            pinIv = itemView.findViewById(R.id.clip_item_pin_iv);
            cardView = (MaterialCardView) itemView;
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_clipboard_history, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        int currentPosition = holder.getAdapterPosition();
        if (currentPosition == RecyclerView.NO_POSITION) return;

        ClipboardHistoryModel model = data.get(currentPosition);

        holder.clipTextTv.setText(model.getClipText());
        holder.clipTextTv.setTextColor(textColor);
        holder.cardView.setCardBackgroundColor(cardColor);
        holder.pinIv.setVisibility(model.isPinned() ? View.VISIBLE : View.GONE);

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            callback.onItemClicked(data.get(pos));
        });

        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return false;
            callback.onItemLongClicked(data.get(pos), pos, v);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    public void removeItem(int position) {
        if (position >= 0 && position < data.size()) {
            data.remove(position);
            notifyItemRemoved(position);
        }
    }

    public ClipboardHistoryModel getItem(int position) {
        if (position >= 0 && position < data.size()) {
            return data.get(position);
        }
        return null;
    }
}
