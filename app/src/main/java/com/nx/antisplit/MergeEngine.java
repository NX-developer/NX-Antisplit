package com.nx.antisplit;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.reandroid.apk.ApkBundle;
import com.reandroid.apk.ApkModule;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Converts a split-APK bundle (XAPK / APKS / APKX / APKM) into a single,
 * standalone, signed APK.
 *
 * Steps:
 *   1. Copy the picked file into app cache.
 *   2. Unzip and collect every *.apk part (base + config splits).
 *   3. Merge them with ARSCLib (resources.arsc + dex + native libs + assets).
 *   4. Re-sign the merged APK so Android will install it.
 */
public class MergeEngine {

    public interface Progress {
        void log(String message);
    }

    private static final String TAG = "NXAntisplit";

    private final Context ctx;
    private final Progress progress;

    public MergeEngine(Context ctx, Progress progress) {
        this.ctx = ctx.getApplicationContext();
        this.progress = progress;
    }

    private void log(String m) {
        Log.d(TAG, m);
        if (progress != null) progress.log(m);
    }

    /** Runs the whole pipeline and returns the final signed APK file. */
    public File process(Uri input, String displayName) throws Exception {
        File work = new File(ctx.getCacheDir(), "work_" + System.currentTimeMillis());
        File extractDir = new File(work, "parts");
        extractDir.mkdirs();

        File outputDir = new File(ctx.getCacheDir(), "output");
        outputDir.mkdirs();

        try {
            log("1/4  Dosya kopyalanıyor…");
            File archive = new File(work, "input.zip");
            copyUriToFile(input, archive);
            log("     Boyut: " + humanSize(archive.length()));

            log("2/4  Arşiv açılıyor (" + displayName + ")…");
            int count = unzipApkParts(archive, extractDir);
            if (count == 0) {
                throw new Exception("Arşivin içinde APK parçası bulunamadı. "
                        + "Seçtiğin dosya zaten düz bir APK olabilir veya desteklenmeyen bir biçimde.");
            }
            log("     " + count + " APK parçası bulundu.");

            log("3/4  Parçalar birleştiriliyor… (büyük dosyalarda biraz sürebilir)");
            File mergedUnsigned = new File(work, "merged-unsigned.apk");
            mergeParts(extractDir, mergedUnsigned);
            log("     Birleştirildi: " + humanSize(mergedUnsigned.length()));

            log("4/4  İmzalanıyor…");
            String baseName = sanitizeName(displayName) + "_antisplit.apk";
            File signed = new File(outputDir, baseName);
            if (signed.exists()) signed.delete();
            ApkSignerHelper.sign(ctx, mergedUnsigned, signed);

            log("✓ Bitti: " + signed.getName() + " (" + humanSize(signed.length()) + ")");
            return signed;
        } finally {
            deleteRecursive(work);
        }
    }

    // ── Unzip ───────────────────────────────────────────────────────────────

    /**
     * Extracts every *.apk entry into {@code outDir}. If the archive contains a
     * {@code splits/} folder (bundletool .apks), only those splits are taken so
     * we don't mix standalone APKs into the merge set.
     */
    private int unzipApkParts(File archive, File outDir) throws Exception {
        boolean hasSplitsFolder = false;
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                String name = e.nextElement().getName().replace('\\', '/');
                if (name.toLowerCase().endsWith(".apk") && name.toLowerCase().contains("splits/")) {
                    hasSplitsFolder = true;
                    break;
                }
            }
        }

        int extracted = 0;
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (!name.toLowerCase().endsWith(".apk")) continue;
                if (hasSplitsFolder && !name.toLowerCase().contains("splits/")) continue;

                String fileName = name.substring(name.lastIndexOf('/') + 1);
                File out = new File(outDir, fileName);
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream os = new FileOutputStream(out)) {
                    copy(in, os);
                }
                extracted++;
            }
        }
        return extracted;
    }

    // ── Merge ────────────────────────────────────────────────────────────────

    private void mergeParts(File partsDir, File outApk) throws Exception {
        ApkBundle bundle = new ApkBundle();
        bundle.loadApkDirectory(partsDir);
        ApkModule merged = bundle.mergeModules();
        clearSplitRequirement(merged);
        if (outApk.exists()) outApk.delete();
        merged.writeApk(outApk);
        try {
            bundle.close();
        } catch (Throwable ignore) {
        }
    }

    /**
     * Best-effort: clears the "splits required" manifest flag so the merged APK
     * installs as a normal standalone app. Done via reflection because the exact
     * method name varies across ARSCLib versions.
     */
    private void clearSplitRequirement(ApkModule merged) {
        try {
            Object manifest = merged.getClass().getMethod("getAndroidManifest").invoke(merged);
            if (manifest == null) return;
            for (String setter : new String[]{"setSplitsRequired", "setIsSplitRequired"}) {
                try {
                    manifest.getClass().getMethod(setter, boolean.class).invoke(manifest, false);
                    log("     Split-required bayrağı temizlendi.");
                    return;
                } catch (NoSuchMethodException ignore) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "clearSplitRequirement atlandı: " + t);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void copyUriToFile(Uri uri, File dest) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(dest)) {
            if (in == null) throw new Exception("Dosya açılamadı.");
            copy(in, os);
        }
    }

    static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.flush();
    }

    static String sanitizeName(String name) {
        if (name == null) name = "output";
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (name.isEmpty()) name = "output";
        return name;
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private List<File> listApks(File dir) {
        List<File> r = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".apk")) r.add(f);
        return r;
    }
}
