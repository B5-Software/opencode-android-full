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

// Test-only control IPC. Only the target app UID can request synthetic grants.
public final class UploadFixtureControlProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String order) { throw new SecurityException(); }
    @Override public Bundle call(String method, String argument, Bundle extras) {
        try { if (android.os.Binder.getCallingUid() != getContext().getPackageManager().getPackageUid("io.github.opencodeandroid", 0)) throw new SecurityException(); }
        catch (android.content.pm.PackageManager.NameNotFoundException error) { throw new SecurityException(error); }
        if ("probeBridgePeer".equals(method)) {
            try { if (android.os.Binder.getCallingUid() != getContext().getPackageManager().getPackageUid("io.github.opencodeandroid", 0)) throw new SecurityException(); }
            catch (android.content.pm.PackageManager.NameNotFoundException error) { throw new SecurityException(error); }
            long identity = android.os.Binder.clearCallingIdentity();
            try (android.net.LocalSocket socket = new android.net.LocalSocket()) {
                Bundle result = new Bundle();
                try {
                    socket.connect(new android.net.LocalSocketAddress(argument, android.net.LocalSocketAddress.Namespace.ABSTRACT));
                    socket.setSoTimeout(3000);
                    String line = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)).readLine();
                    result.putString("result", new org.json.JSONObject(line).getString("error"));
                } catch (java.io.IOException error) { result.putString("result", "transport_denied"); }
                catch (org.json.JSONException error) { throw new IllegalStateException(error); }
                return result;
            } catch (java.io.IOException error) { throw new IllegalStateException(error); }
            finally { android.os.Binder.restoreCallingIdentity(identity); }
        }
        if ("readSharedFixture".equals(method)) {
            try {
                if (android.os.Binder.getCallingUid() != getContext().getPackageManager().getPackageUid("io.github.opencodeandroid", 0)) throw new SecurityException();
            } catch (android.content.pm.PackageManager.NameNotFoundException error) { throw new SecurityException(error); }
            long identity = android.os.Binder.clearCallingIdentity();
            try {
                Bundle result = new Bundle();
                Uri uri = Uri.parse(argument);
                if (!"io.github.opencodeandroid.files".equals(uri.getAuthority())) throw new SecurityException();
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream input = getContext().getContentResolver().openInputStream(uri)) {
                    byte[] buffer = new byte[1024]; int count;
                    while ((count = input.read(buffer)) >= 0) { if (bytes.size() + count > 4096) throw new SecurityException(); bytes.write(buffer, 0, count); }
                } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                result.putString("text", new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                boolean denied = false;
                try (java.io.OutputStream output = getContext().getContentResolver().openOutputStream(uri, "wt")) { output.write(1); }
                catch (SecurityException error) { denied = true; }
                catch (java.io.IOException error) { throw new IllegalStateException(error); }
                result.putBoolean("writeDenied", denied);
                return result;
            } finally { android.os.Binder.restoreCallingIdentity(identity); }
        }
        if (!"grantFixture".equals(method) || !"io.github.opencodeandroid".equals(argument)) throw new SecurityException();
        long identity = android.os.Binder.clearCallingIdentity();
        try {
            for (String name : new String[]{"pixel.png", "note.txt"}) getContext().grantUriPermission(argument, Uri.parse("content://io.github.opencodeandroid.test.upload/" + name), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getContext().grantUriPermission(argument, Uri.parse("content://io.github.opencodeandroid.test.upload/export.txt"), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } finally { android.os.Binder.restoreCallingIdentity(identity); }
        return new Bundle();
    }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
