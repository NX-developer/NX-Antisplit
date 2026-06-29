package com.nx.antisplit;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private TextView logView;
    private ScrollView logScroll;
    private Button pickBtn, saveBtn, installBtn;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private File resultApk;
    private String resultName = "output.apk";

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) startProcess(uri);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        logView = findViewById(R.id.logView);
        logScroll = findViewById(R.id.logScroll);
        pickBtn = findViewById(R.id.pickBtn);
        saveBtn = findViewById(R.id.saveBtn);
        installBtn = findViewById(R.id.installBtn);

        logView.setMovementMethod(new ScrollingMovementMethod());

        pickBtn.setOnClickListener(v -> picker.launch(new String[]{"*/*"}));
        saveBtn.setOnClickListener(v -> saveResult());
        installBtn.setOnClickListener(v -> installResult());

        setResultButtonsEnabled(false);
        log("NX Antisplit hazır.");
        log("XAPK / APKS / APKX / APKM seç → düz, kurulabilir APK çıkar.\n");
    }

    private void startProcess(Uri uri) {
        resultApk = null;
        setResultButtonsEnabled(false);
        pickBtn.setEnabled(false);
        logView.setText("");

        final String displayName = queryDisplayName(uri);
        resultName = MergeEngine.sanitizeName(displayName) + "_antisplit.apk";

        executor.execute(() -> {
            try {
                MergeEngine engine = new MergeEngine(MainActivity.this, this::log);
                File out = engine.process(uri, displayName);
                ui.post(() -> {
                    resultApk = out;
                    pickBtn.setEnabled(true);
                    setResultButtonsEnabled(true);
                    toast("Hazır! Kaydet veya Yükle.");
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    log("\n✗ HATA: " + t.getMessage());
                    pickBtn.setEnabled(true);
                    toast("İşlem başarısız.");
                });
            }
        });
    }

    // ── Save to Downloads ─────────────────────────────────────────────────────

    private void saveResult() {
        if (resultApk == null) return;
        executor.execute(() -> {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, resultName);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri item = getContentResolver().insert(collection, cv);
                if (item == null) throw new Exception("MediaStore kaydı oluşturulamadı.");
                try (OutputStream os = getContentResolver().openOutputStream(item);
                     InputStream in = new java.io.FileInputStream(resultApk)) {
                    MergeEngine.copy(in, os);
                }
                cv.clear();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(item, cv, null, null);
                ui.post(() -> {
                    log("✓ Kaydedildi: Download/" + resultName);
                    toast("Download klasörüne kaydedildi.");
                });
            } catch (Throwable t) {
                ui.post(() -> log("✗ Kaydetme hatası: " + t.getMessage()));
            }
        });
    }

    // ── Install ───────────────────────────────────────────────────────────────

    private void installResult() {
        if (resultApk == null) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            toast("Önce 'Bilinmeyen kaynaklara izin ver' aç.");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignore) {
            }
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", resultApk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String queryDisplayName(Uri uri) {
        String name = "output";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && !n.isEmpty()) name = n;
                }
            }
        } catch (Exception ignore) {
        }
        return name;
    }

    private void setResultButtonsEnabled(boolean enabled) {
        saveBtn.setEnabled(enabled);
        installBtn.setEnabled(enabled);
    }

    private void log(String msg) {
        ui.post(() -> {
            logView.append(msg + "\n");
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
