package com.aimediasorter.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Test APK components cannot depend on Kotlin libraries excluded from its standalone process. */
public final class FixtureGrantActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String testPackage = getPackageName();
        if (!testPackage.endsWith(".test")) throw new SecurityException("Unexpected fixture package");
        String target = testPackage.substring(0, testPackage.length() - 5);
        try {
            if (getPackageManager().checkSignatures(getPackageName(), target) != PackageManager.SIGNATURE_MATCH)
                throw new SecurityException("Untrusted fixture target");
            String token = getIntent().getStringExtra("qa.token");
            if (token == null || token.length() > 128) throw new IllegalArgumentException("Missing test token");
            String authority = "com.aimediasorter.app.qa.documents";
            Uri control = Uri.parse("content://" + authority + "/control");
            Bundle extras = new Bundle();
            extras.putString("token", token);
            getContentResolver().call(control, "qa.reset", null, extras);
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            for (String root : new String[]{"source", "destination"})
                grantUriPermission(target, DocumentsContract.buildTreeDocumentUri(authority, root),
                    flags | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            grantUriPermission(target, control, flags);
        } finally { finish(); }
    }
}
