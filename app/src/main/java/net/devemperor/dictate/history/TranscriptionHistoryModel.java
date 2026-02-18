package net.devemperor.dictate.history;

public class TranscriptionHistoryModel {
    int id;
    String audioFileName;
    String transcriptionText;
    float durationSeconds;
    long createdAt;
    String language;

    public TranscriptionHistoryModel(int id, String audioFileName, String transcriptionText, float durationSeconds, long createdAt, String language) {
        this.id = id;
        this.audioFileName = audioFileName;
        this.transcriptionText = transcriptionText;
        this.durationSeconds = durationSeconds;
        this.createdAt = createdAt;
        this.language = language;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getAudioFileName() {
        return audioFileName;
    }

    public void setAudioFileName(String audioFileName) {
        this.audioFileName = audioFileName;
    }

    public String getTranscriptionText() {
        return transcriptionText;
    }

    public void setTranscriptionText(String transcriptionText) {
        this.transcriptionText = transcriptionText;
    }

    public float getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(float durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }
}
