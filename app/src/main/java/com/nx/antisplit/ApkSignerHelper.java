package com.nx.antisplit;

import android.content.Context;

import com.android.apksig.ApkSigner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collections;
import java.util.List;

/**
 * Signs the merged APK using a bundled test key (assets/testkey.pk8 +
 * testkey.x509.der). The signature is self-signed, so the output installs as a
 * "from this tool" app rather than carrying the original developer signature —
 * that is unavoidable when re-packing split APKs.
 */
final class ApkSignerHelper {

    private ApkSignerHelper() {}

    static void sign(Context ctx, File input, File output) throws Exception {
        PrivateKey privateKey = loadPrivateKey(ctx);
        X509Certificate certificate = loadCertificate(ctx);

        ApkSigner.SignerConfig signerConfig = buildSignerConfig("nx", privateKey, certificate);

        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(input)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .build();
        signer.sign();
    }

    /**
     * Builds a SignerConfig that works across apksig API variants:
     *  - classic: Builder(String, PrivateKey, List&lt;X509Certificate&gt;)
     *  - newer:   Builder(String, KeyConfig, List&lt;X509Certificate&gt;) with KeyConfig.Jca
     */
    private static ApkSigner.SignerConfig buildSignerConfig(
            String name, PrivateKey key, X509Certificate cert) throws Exception {
        List<X509Certificate> certs = Collections.singletonList(cert);
        Object builder;
        try {
            Constructor<ApkSigner.SignerConfig.Builder> c =
                    ApkSigner.SignerConfig.Builder.class.getConstructor(
                            String.class, PrivateKey.class, List.class);
            builder = c.newInstance(name, key, certs);
        } catch (NoSuchMethodException e) {
            Class<?> keyConfigCls = Class.forName("com.android.apksig.KeyConfig");
            Class<?> jcaCls = Class.forName("com.android.apksig.KeyConfig$Jca");
            Object jca = jcaCls.getConstructor(PrivateKey.class).newInstance(key);
            builder = ApkSigner.SignerConfig.Builder.class
                    .getConstructor(String.class, keyConfigCls, List.class)
                    .newInstance(name, jca, certs);
        }
        return (ApkSigner.SignerConfig) builder.getClass().getMethod("build").invoke(builder);
    }

    private static PrivateKey loadPrivateKey(Context ctx) throws Exception {
        byte[] pk8 = readAsset(ctx, "testkey.pk8");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pk8);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private static X509Certificate loadCertificate(Context ctx) throws Exception {
        byte[] der = readAsset(ctx, "testkey.x509.der");
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (InputStream in = new java.io.ByteArrayInputStream(der)) {
            return (X509Certificate) cf.generateCertificate(in);
        }
    }

    private static byte[] readAsset(Context ctx, String name) throws Exception {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
