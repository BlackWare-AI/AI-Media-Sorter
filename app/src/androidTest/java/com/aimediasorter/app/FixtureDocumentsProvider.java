package com.aimediasorter.app;

import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.MessageDigest;

/** Isolated fault-injection files only; this provider exists exclusively in the test APK. */
public final class FixtureDocumentsProvider extends DocumentsProvider {
    private boolean revoke, corrupt, timestampUnsupported;
    private String readyToken;
    private File base() { return new File(getContext().getFilesDir(), "qa-documents"); }
    @Override public boolean onCreate() { return true; }
    private void enforceCaller() {
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.myUid()) return;
        String testPackage = getContext().getPackageName();
        if (!testPackage.endsWith(".test")) throw new SecurityException("Unexpected fixture package");
        String target = testPackage.substring(0, testPackage.length() - 5);
        boolean trusted = false;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages != null) for (String name : packages) if (target.equals(name)) trusted = true;
        if (!trusted || getContext().getPackageManager().checkSignatures(
                getContext().getPackageName(), target) != PackageManager.SIGNATURE_MATCH)
            throw new SecurityException("Untrusted QA provider caller");
    }
    private File file(String id) {
        enforceCaller();
        File f = new File(base(), id);
        try {
            if (!f.getCanonicalPath().startsWith(base().getCanonicalPath() + File.separator))
                throw new SecurityException("Fixture path escape");
        } catch (IOException error) { throw new IllegalStateException(error); }
        return f;
    }
    private static void remove(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        if (file.exists() && !file.delete()) throw new IllegalStateException("Fixture cleanup failed");
    }
    private static String hash(File f) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (java.io.FileInputStream input = new java.io.FileInputStream(f)) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = input.read(buffer)) != -1) hash.update(buffer, 0, n);
            }
            StringBuilder value = new StringBuilder();
            for (byte b : hash.digest()) value.append(String.format(java.util.Locale.US, "%02x", b & 255));
            return value.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        enforceCaller();
        if ("qa.reset".equals(method)) {
            remove(base());
            if (!new File(base(), "source").mkdirs() || !new File(base(), "destination").mkdirs())
                throw new IllegalStateException("Fixture directories unavailable");
            File source = new File(base(), "source/photo.jpg");
            byte[] payload = new byte[4096];
            for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 251);
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(source)) {
                output.write(payload);
            } catch (IOException error) { throw new IllegalStateException(error); }
            if (!source.setLastModified(1700000000000L)) throw new IllegalStateException("Fixture mtime unavailable");
            revoke = corrupt = timestampUnsupported = false;
            readyToken = extras == null ? null : extras.getString("token");
            return new Bundle();
        }
        if ("qa.fault".equals(method)) {
            revoke = "revoke".equals(arg);
            corrupt = "corrupt".equals(arg);
            timestampUnsupported = "timestamp".equals(arg);
            return new Bundle();
        }
        if ("qa.state".equals(method)) {
            Bundle state = new Bundle();
            state.putString("token", readyToken);
            File source = new File(base(), "source/photo.jpg");
            state.putBoolean("sourceExists", source.exists());
            if (source.exists()) state.putString("sourceHash", hash(source));
            return state;
        }
        if ("qa.cleanup".equals(method)) { remove(base()); return new Bundle(); }
        return super.call(method, arg, extras);
    }
    @Override public Cursor queryRoots(String[] projection) {
        enforceCaller();
        return new MatrixCursor(projection == null ? new String[]{DocumentsContract.Root.COLUMN_ROOT_ID} : projection);
    }
    private MatrixCursor cursor(String[] projection) {
        return new MatrixCursor(projection == null ? new String[]{
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED} : projection);
    }
    private void row(MatrixCursor cursor, String id) throws FileNotFoundException {
        File f = file(id);
        if (!f.exists()) throw new FileNotFoundException(id);
        int flags = f.isDirectory() ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE :
            DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
        Object[] values = new Object[cursor.getColumnCount()];
        String[] columns = cursor.getColumnNames();
        for (int i = 0; i < columns.length; i++) {
            String c = columns[i];
            if (c.equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) values[i] = id;
            else if (c.equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) values[i] = f.getName();
            else if (c.equals(DocumentsContract.Document.COLUMN_MIME_TYPE))
                values[i] = f.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "image/jpeg";
            else if (c.equals(DocumentsContract.Document.COLUMN_FLAGS)) values[i] = flags;
            else if (c.equals(DocumentsContract.Document.COLUMN_SIZE)) values[i] = f.length();
            else if (c.equals(DocumentsContract.Document.COLUMN_LAST_MODIFIED))
                values[i] = timestampUnsupported ? 0L : f.lastModified();
        }
        cursor.addRow(values);
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        if (revoke) throw new SecurityException("Injected revoked access");
        MatrixCursor cursor = cursor(projection);
        row(cursor, id);
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String order) throws FileNotFoundException {
        if (revoke) throw new SecurityException("Injected revoked access");
        MatrixCursor cursor = cursor(projection);
        File[] children = file(id).listFiles();
        if (children != null) for (File child : children) row(cursor, id + "/" + child.getName());
        return cursor;
    }
    @Override public boolean isChildDocument(String parent, String id) {
        enforceCaller();
        return id.startsWith(parent + "/");
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (revoke) throw new SecurityException("Injected revoked access");
        File f = file(id);
        if (corrupt && "r".equals(mode) && id.startsWith("destination/") && f.isFile()) {
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(f)) {
                output.write(new byte[]{0, 9, 1});
            } catch (IOException error) { throw new IllegalStateException(error); }
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (name.contains("/") || name.equals(".") || name.equals("..")) throw new IllegalArgumentException("Invalid fixture name");
        String original = parent + "/" + name, id = original;
        int suffix = 1;
        while (file(id).exists()) id = original + "-" + suffix++;
        File f = file(id);
        try {
            boolean created = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime) ? f.mkdir() : f.createNewFile();
            if (!created) throw new IllegalStateException("Fixture creation failed");
        } catch (IOException error) { throw new IllegalStateException(error); }
        return id;
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (revoke) throw new SecurityException("Injected revoked access");
        if (!file(id).delete()) throw new IllegalStateException("Fixture deletion failed");
    }
}
