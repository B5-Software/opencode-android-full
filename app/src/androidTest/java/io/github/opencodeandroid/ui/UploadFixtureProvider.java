package io.github.opencodeandroid.ui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;

// The standalone test provider uses only platform classes: the test APK does
// not duplicate Kotlin runtime classes supplied by the target application.
public final class UploadFixtureProvider extends ContentProvider {
    public static final String PIXEL = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aO9sAAAAASUVORK5CYII=";
    @Override public boolean onCreate() {
        for (String name : new String[]{"pixel.png", "note.txt"}) {
            try (FileOutputStream output = new FileOutputStream(new File(getContext().getCacheDir(), name))) {
                output.write(bytes(Uri.parse("content://io.github.opencodeandroid.test.upload/" + name)));
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        }
        try { new File(getContext().getCacheDir(), "export.txt").createNewFile(); }
        catch (java.io.IOException error) { throw new IllegalStateException(error); }
        return true;
    }
    private byte[] bytes(Uri uri) { return "pixel.png".equals(uri.getLastPathSegment()) ? Base64.decode(PIXEL, Base64.DEFAULT) : "UPLOAD_DOCUMENT_OK".getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    @Override public String getType(Uri uri) { return "pixel.png".equals(uri.getLastPathSegment()) ? "image/png" : "text/plain"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{uri.getLastPathSegment(), bytes(uri).length});
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if ("export.txt".equals(name)) {
            if ("r".equals(mode)) return ParcelFileDescriptor.open(new File(getContext().getCacheDir(), name), ParcelFileDescriptor.MODE_READ_ONLY);
            if ("w".equals(mode) || "wt".equals(mode)) return ParcelFileDescriptor.open(new File(getContext().getCacheDir(), name), ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE);
            throw new FileNotFoundException();
        }
        if (!"r".equals(mode) || !("pixel.png".equals(name) || "note.txt".equals(name))) throw new FileNotFoundException();
        File file = new File(getContext().getCacheDir(), name);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
