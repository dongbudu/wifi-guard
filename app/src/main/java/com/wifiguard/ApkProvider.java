package com.wifiguard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 给系统安装器读取下载好的 APK 用。
 * 没用 AndroidX 的 FileProvider，所以自己实现一个最小 Provider
 * （android:authorities 必须和这里一致）。
 */
public class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "com.wifiguard.apk";
    public static final String MIME = "application/vnd.android.package-archive";

    public static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/update.apk");
    }

    public static File apkFile(android.content.Context c) {
        return new File(c.getCacheDir(), "update.apk");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = new File(getContext().getCacheDir(), "update.apk");
        MatrixCursor c = new MatrixCursor(
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{"WiFiGuard-update.apk", f.length()});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return MIME;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = new File(getContext().getCacheDir(), "update.apk");
        if (!f.exists()) throw new FileNotFoundException(f.getAbsolutePath());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
