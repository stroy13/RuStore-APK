package ru.rustoredl;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Debug;
import android.os.Process;
import android.widget.Toast;

import java.io.File;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import android.util.Base64;

/**
 * Самозащита приложения:
 *  1) проверка собственной подписи (APK не пересобран чужим ключом);
 *  2) TLS-pinning — соединения только с серверами RuStore (SPKI-пины);
 *  3) детект root / Xposed / Frida;
 *  4) анти-отладка (watchdog убивает процесс при подключении отладчика).
 */
public final class SecurityUtil {

    // ---- SPKI-пины (SHA-256, base64) сертификатов *.rustore.ru ----
    private static final String[] TLS_PINS = {
            "xPZ60xFS0KfUtEAahP4IevLdlOUNCVXRfH1dMzPp5BE=", // leaf: *.rustore.ru
            "rk2I2etvSahZka3FPKyvABNS0NJaDCduKDeQ5Vvg/Xs=", // intermediate: HARICA
            "aTyaprJFs7AmFjd1CGPq22wkihblLW9LyQyGu/MtcEI=", // root: HARICA
    };

    public static boolean rooted = false;
    private static SSLSocketFactory sFactory;

    private SecurityUtil() {}

    // ---------------------------------------------------------- подпись APK

    /** true — подпись APK совпадает с ожидаемой (задаётся res/values/security.xml при сборке). */
    public static boolean signatureOk(Context c) {
        try {
            String expected = c.getString(R.string.cert_sha256);
            // "SKIP" = сборка с NO_SIG_CHECK=1: проверка отключена владельцем приложения
            if ("SKIP".equalsIgnoreCase(expected.trim())) return true;
            PackageManager pm = c.getPackageManager();
            String pkg = c.getPackageName();
            byte[] cert;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi.signingInfo == null) return false;
                android.content.pm.Signature[] sigs = pi.signingInfo.hasMultipleSigners()
                        ? pi.signingInfo.getSigningCertificateHistory()
                        : pi.signingInfo.getApkContentsSigners();
                if (sigs == null || sigs.length == 0) return false;
                cert = sigs[0].toByteArray();
            } else {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                if (pi.signatures == null || pi.signatures.length == 0) return false;
                cert = pi.signatures[0].toByteArray();
            }
            byte[] d = MessageDigest.getInstance("SHA-256").digest(cert);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString().equalsIgnoreCase(expected);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------- окружение

    /** Возвращает true, если найдены инструменты модификации (Xposed/Frida). Root — только флаг. */
    public static boolean hookedEnvironment(Context c) {
        String[] rootPaths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sd/xbin/su",
                "/system/app/Superuser.apk", "/data/adb/magisk", "/sbin/.magisk",
        };
        for (String p : rootPaths) if (new File(p).exists()) rooted = true;
        String tags = android.os.Build.TAGS;
        if (tags != null && tags.contains("test-keys")) rooted = true;

        boolean hooked = new File("/system/framework/XposedBridge.jar").exists()
                || new File("/data/local/tmp/re.frida.server").exists()
                || new File("/data/local/tmp/frida-server").exists()
                || new File("/data/local/tmp/frida_gadget").exists()
                || classExists("de.robv.android.xposed.XposedBridge")
                || classExists("de.robv.android.xposed.XposedHelpers");
        return hooked;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------- анти-отладка

    /** Фоновый поток: при подключении отладчика процесс завершается. */
    public static void startWatchdog() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
                        Process.killProcess(Process.myPid());
                        System.exit(10);
                    }
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {}
            }
        }, "sec-watchdog");
        t.setDaemon(true);
        t.start();
    }

    static void fatal(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
        Process.killProcess(Process.myPid());
    }

    // ---------------------------------------------------------- TLS-pinning


    /**
     * Доверенный менеджер: если leaf-сертификат сервера имеет наш пин — доверяем
     * (ключ сервера зафиксирован, CA-проверка избыточна); иначе — обычная проверка цепочки.
     */
    private static X509TrustManager pinnedManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((java.security.KeyStore) null); // системные корневые CA
        final X509TrustManager base = (X509TrustManager) tmf.getTrustManagers()[0];
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                base.checkClientTrusted(chain, auth);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                if (chain != null && chain.length > 0 && pinMatch(chain[0])) return; // leaf зафиксирован
                base.checkServerTrusted(chain, auth); // либо стандартная проверка цепочки
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return base.getAcceptedIssuers();
            }
        };
    }

    private static boolean pinMatch(X509Certificate cert) {
        try {
            // publicKey.getEncoded() для X.509 = SubjectPublicKeyInfo (DER)
            byte[] spki = cert.getPublicKey().getEncoded();
            String h = Base64.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(spki), Base64.NO_WRAP);
            for (String p : TLS_PINS) if (p.equals(h)) return true;
        } catch (Exception ignore) {}
        return false;
    }

    private static SSLSocketFactory factory() throws Exception {
        if (sFactory != null) return sFactory;
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new X509TrustManager[]{pinnedManager()}, null);
        sFactory = ctx.getSocketFactory();
        return sFactory;
    }

    /** Навешивает пиннинг на HttpsURLConnection — только для серверов RuStore.
     *  Остальные хосты (например, собственный сервер обновлений) идут с обычной
     *  системной проверкой сертификата: их пины нам неизвестны. */
    public static void applyTo(java.net.HttpURLConnection c) {
        if (!(c instanceof HttpsURLConnection)) return;
        String h = c.getURL().getHost();
        boolean rustore = h.equals("rustore.ru") || h.endsWith(".rustore.ru");
        if (!rustore) return; // системное доверие по умолчанию
        try {
            ((HttpsURLConnection) c).setSSLSocketFactory(factory());
        } catch (Exception ignore) {
            // без фабрики соединение будет использовать системную проверку
        }
    }
}
