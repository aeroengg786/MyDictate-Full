package net.devemperor.dictate.history;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TranscriptionHistoryDatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "history.db";
    private static final int DATABASE_VERSION = 1;

    public TranscriptionHistoryDatabaseHelper(@Nullable Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase sqLiteDatabase) {
        sqLiteDatabase.execSQL("CREATE TABLE TRANSCRIPTION_HISTORY (ID INTEGER PRIMARY KEY AUTOINCREMENT, AUDIO_FILE_NAME TEXT, TRANSCRIPTION_TEXT TEXT, DURATION_SECONDS REAL, CREATED_AT INTEGER, LANGUAGE TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase sqLiteDatabase, int oldVersion, int newVersion) {
    }

    public int add(TranscriptionHistoryModel model) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("AUDIO_FILE_NAME", model.getAudioFileName());
        cv.put("TRANSCRIPTION_TEXT", model.getTranscriptionText());
        cv.put("DURATION_SECONDS", model.getDurationSeconds());
        cv.put("CREATED_AT", model.getCreatedAt());
        cv.put("LANGUAGE", model.getLanguage());
        return (int) db.insert("TRANSCRIPTION_HISTORY", null, cv);
    }

    public List<TranscriptionHistoryModel> getAll() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM TRANSCRIPTION_HISTORY ORDER BY CREATED_AT DESC", null);

        List<TranscriptionHistoryModel> models = new ArrayList<>();
        if (cursor.moveToFirst()) {
            do {
                models.add(new TranscriptionHistoryModel(cursor.getInt(0), cursor.getString(1), cursor.getString(2), cursor.getFloat(3), cursor.getLong(4), cursor.getString(5)));
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return models;
    }

    public void delete(int id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("TRANSCRIPTION_HISTORY", "ID = " + id, null);
        db.close();
    }

    public List<TranscriptionHistoryModel> getOldEntries(int maxEntries, long retentionMillis) {
        SQLiteDatabase db = getReadableDatabase();
        Set<Integer> seenIds = new HashSet<>();
        List<TranscriptionHistoryModel> result = new ArrayList<>();

        // Get entries older than retention period
        long cutoff = System.currentTimeMillis() - retentionMillis;
        Cursor cursor = db.rawQuery("SELECT * FROM TRANSCRIPTION_HISTORY WHERE CREATED_AT < " + cutoff, null);
        if (cursor.moveToFirst()) {
            do {
                TranscriptionHistoryModel model = new TranscriptionHistoryModel(cursor.getInt(0), cursor.getString(1), cursor.getString(2), cursor.getFloat(3), cursor.getLong(4), cursor.getString(5));
                seenIds.add(model.getId());
                result.add(model);
            } while (cursor.moveToNext());
        }
        cursor.close();

        // Get entries that exceed maxEntries (skip the newest maxEntries, return the rest)
        Cursor overflowCursor = db.rawQuery("SELECT * FROM TRANSCRIPTION_HISTORY ORDER BY CREATED_AT DESC LIMIT -1 OFFSET " + maxEntries, null);
        if (overflowCursor.moveToFirst()) {
            do {
                TranscriptionHistoryModel model = new TranscriptionHistoryModel(overflowCursor.getInt(0), overflowCursor.getString(1), overflowCursor.getString(2), overflowCursor.getFloat(3), overflowCursor.getLong(4), overflowCursor.getString(5));
                if (!seenIds.contains(model.getId())) {
                    seenIds.add(model.getId());
                    result.add(model);
                }
            } while (overflowCursor.moveToNext());
        }
        overflowCursor.close();
        db.close();
        return result;
    }

    public int count() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM TRANSCRIPTION_HISTORY", null);
        cursor.moveToFirst();
        int count = cursor.getInt(0);
        cursor.close();
        db.close();
        return count;
    }
}
