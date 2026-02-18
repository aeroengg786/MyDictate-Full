package net.devemperor.dictate.clipboard;

public class ClipboardHistoryModel {
    int id;
    String clipText;
    long createdAt;
    boolean isPinned;

    public ClipboardHistoryModel(int id, String clipText, long createdAt, boolean isPinned) {
        this.id = id;
        this.clipText = clipText;
        this.createdAt = createdAt;
        this.isPinned = isPinned;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getClipText() {
        return clipText;
    }

    public void setClipText(String clipText) {
        this.clipText = clipText;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isPinned() {
        return isPinned;
    }

    public void setPinned(boolean pinned) {
        isPinned = pinned;
    }
}
