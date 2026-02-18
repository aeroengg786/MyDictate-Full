package net.devemperor.dictate.clipboard;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class ClipboardHistoryDatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "clipboard.db";
    private static final int DATABASE_VERSION = 1;

    public ClipboardHistoryDatabaseHelper(@Nullable Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE CLIPBOARD_HISTORY (" +
                "ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "CLIP_TEXT TEXT, " +
                "CREATED_AT INTEGER, " +
                "IS_PINNED INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public int add(String text) {
        SQLiteDatabase db = getWritableDatabase();
        // Deduplicate: if same text exists, update timestamp and return existing ID
        Cursor cursor = db.rawQuery("SELECT ID, IS_PINNED FROM CLIPBOARD_HISTORY WHERE CLIP_TEXT = ?", new String[]{text});
        if (cursor.moveToFirst()) {
            int existingId = cursor.getInt(0);
            cursor.close();
            ContentValues cv = new ContentValues();
            cv.put("CREATED_AT", System.currentTimeMillis());
            db.update("CLIPBOARD_HISTORY", cv, "ID = ?", new String[]{String.valueOf(existingId)});
            return existingId;
        }
        cursor.close();

        ContentValues cv = new ContentValues();
        cv.put("CLIP_TEXT", text);
        cv.put("CREATED_AT", System.currentTimeMillis());
        cv.put("IS_PINNED", 0);
        return (int) db.insert("CLIPBOARD_HISTORY", null, cv);
    }

    public List<ClipboardHistoryModel> getAll() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT ID, CLIP_TEXT, CREATED_AT, IS_PINNED FROM CLIPBOARD_HISTORY ORDER BY IS_PINNED DESC, CREATED_AT DESC", null);

        List<ClipboardHistoryModel> models = new ArrayList<>();
        if (cursor.moveToFirst()) {
            do {
                models.add(new ClipboardHistoryModel(
                        cursor.getInt(0),
                        cursor.getString(1),
                        cursor.getLong(2),
                        cursor.getInt(3) == 1));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return models;
    }

    public void pin(int id, boolean pinned) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("IS_PINNED", pinned ? 1 : 0);
        db.update("CLIPBOARD_HISTORY", cv, "ID = ?", new String[]{String.valueOf(id)});
    }

    public void delete(int id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("CLIPBOARD_HISTORY", "ID = ?", new String[]{String.valueOf(id)});
    }

    public void deleteOldUnpinned(long maxAgeMillis, int maxItems) {
        SQLiteDatabase db = getWritableDatabase();

        // Delete unpinned items older than maxAge
        long cutoff = System.currentTimeMillis() - maxAgeMillis;
        db.delete("CLIPBOARD_HISTORY", "IS_PINNED = 0 AND CREATED_AT < ?", new String[]{String.valueOf(cutoff)});

        // Delete unpinned overflow beyond maxItems (keep the newest maxItems unpinned entries)
        db.execSQL("DELETE FROM CLIPBOARD_HISTORY WHERE IS_PINNED = 0 AND ID NOT IN " +
                "(SELECT ID FROM CLIPBOARD_HISTORY WHERE IS_PINNED = 0 ORDER BY CREATED_AT DESC LIMIT ?)",
                new Object[]{maxItems});
    }

    public int count() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM CLIPBOARD_HISTORY", null);
        cursor.moveToFirst();
        int count = cursor.getInt(0);
        cursor.close();
        return count;
    }
}
