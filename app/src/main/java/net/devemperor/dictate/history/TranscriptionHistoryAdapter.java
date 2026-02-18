package net.devemperor.dictate.history;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import net.devemperor.dictate.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TranscriptionHistoryAdapter extends RecyclerView.Adapter<TranscriptionHistoryAdapter.RecyclerViewHolder> {

    private final List<TranscriptionHistoryModel> data;
    private final HistoryCallback callback;
    private final int accentColor;
    public int currentlyPlayingPosition = -1;

    public interface HistoryCallback {
        void onItemClicked(TranscriptionHistoryModel model);
        void onPlayClicked(TranscriptionHistoryModel model, int position);
    }

    public TranscriptionHistoryAdapter(List<TranscriptionHistoryModel> data, HistoryCallback callback, int accentColor) {
        this.data = data;
        this.callback = callback;
        this.accentColor = accentColor;
    }

    public static class RecyclerViewHolder extends RecyclerView.ViewHolder {
        final TextView textPreviewTv;
        final TextView detailsTv;
        final MaterialButton playBtn;

        public RecyclerViewHolder(View itemView) {
            super(itemView);
            textPreviewTv = itemView.findViewById(R.id.history_item_text_tv);
            detailsTv = itemView.findViewById(R.id.history_item_details_tv);
            playBtn = itemView.findViewById(R.id.history_item_play_btn);
        }
    }

    @NonNull
    @Override
    public RecyclerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transcription_history, parent, false);
        return new RecyclerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerViewHolder holder, final int position) {
        int currentPosition = holder.getAdapterPosition();
        if (currentPosition == RecyclerView.NO_POSITION) return;

        TranscriptionHistoryModel model = data.get(currentPosition);

        holder.textPreviewTv.setText(model.getTranscriptionText());

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        String dateStr = sdf.format(new Date(model.getCreatedAt()));

        int totalSeconds = Math.round(model.getDurationSeconds());
        String durationStr;
        if (totalSeconds < 60) {
            durationStr = totalSeconds + "s";
        } else {
            int minutes = totalSeconds / 60;
            int seconds = totalSeconds % 60;
            durationStr = minutes + "m " + seconds + "s";
        }

        holder.detailsTv.setText(dateStr + " \u00b7 " + durationStr);

        holder.playBtn.setBackgroundColor(accentColor);
        holder.playBtn.setForeground(AppCompatResources.getDrawable(holder.itemView.getContext(),
                currentPosition == currentlyPlayingPosition
                        ? R.drawable.ic_baseline_stop_24
                        : R.drawable.ic_baseline_play_arrow_24));

        holder.playBtn.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            callback.onPlayClicked(data.get(pos), pos);
        });

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            callback.onItemClicked(data.get(pos));
        });
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
            return;
        }

        for (Object payload : payloads) {
            if ("play_state".equals(payload)) {
                holder.playBtn.setForeground(AppCompatResources.getDrawable(holder.itemView.getContext(),
                        position == currentlyPlayingPosition
                                ? R.drawable.ic_baseline_stop_24
                                : R.drawable.ic_baseline_play_arrow_24));
            }
        }
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    public void updatePlayButton(int position, boolean isPlaying) {
        int previousPosition = currentlyPlayingPosition;
        currentlyPlayingPosition = isPlaying ? position : -1;

        if (previousPosition >= 0 && previousPosition < data.size()) {
            notifyItemChanged(previousPosition, "play_state");
        }
        if (position >= 0 && position < data.size()) {
            notifyItemChanged(position, "play_state");
        }
    }
}
