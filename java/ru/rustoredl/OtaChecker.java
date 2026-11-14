package ru.rustoredl;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Обновления по воздуху с собственного сервера: приложение читает маленький
 * манифест update.json (версия, размер, SHA-256, «что нового») и при более
 * свежей версии показывает баннер / присылает уведомление. APK скачивается
 * тем же движком загрузки, сверяется контрольная сумма — битый или
 * подменённый файл не установится.
 *
 * ВАЖНО: TLS-пиннинг (SecurityUtil) применяется только к серверам RuStore;
 * собственный хост проверяется обычным системным доверием.
 */
public final class OtaChecker {

    /** Адрес манифеста на хостинге (reg.ru), папка rustoredl в корне сайта. */
    public static final String MANIFEST_URL =
            "https://rslmod.ru/rustoredl/update.json";

    /** Описатель доступной версии. */
    public static class Info {
        public String version;
        public int verCode;
        public String url, sha256, notes;
        public long size;
    }

    private OtaChecker() {}

    /** Скачивает и разбирает манифест; бросает исключение при сетевой ошибке. */
    public static Info fetch() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        StringBuilder sb = new StringBuilder();
        InputStream in = c.getInputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        in.close();
        c.disconnect();
        JSONObject o = new JSONObject(sb.toString());
        Info i = new Info();
        i.version = o.optString("version", "?");
        i.verCode = o.optInt("verCode", 0);
        i.url = o.optString("url", "");
        i.sha256 = o.optString("sha256", "").trim().toLowerCase();
        // notes: строка ИЛИ список строк (удобно править на хостинге — по строке на пункт)
        Object n = o.opt("notes");
        if (n instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) n;
            StringBuilder nb = new StringBuilder();
            for (int k = 0; k < a.length(); k++) {
                String t = a.optString(k, "").trim();
                if (t.isEmpty()) continue;
                if (nb.length() > 0) nb.append('\n');
                nb.append(t);
            }
            i.notes = nb.toString();
        } else {
            i.notes = o.optString("notes", "").trim();
        }
        i.size = o.optLong("size", 0);
        if (i.url == null || i.url.isEmpty()) throw new Exception("манифест без url");
        return i;
    }

    /** Код версии этого приложения. */
    static int ownVerCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** Есть ли более свежая версия на сервере (тихо; null — нет/ошибка/выключено). */
    public static Info checkQuiet(Context c) {
        try {
            if (!c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                    .getBoolean("otaAuto", true)) {
                android.util.Log.d("RuStoreOTA", "checkQuiet: otaAuto OFF");
                return null;
            }
            Info i = fetch();
            int own = ownVerCode(c);
            android.util.Log.d("RuStoreOTA", "checkQuiet server=" + i.version + "/" + i.verCode + " own=" + own + " url=" + i.url);
            return i.verCode > own ? i : null;
        } catch (Exception e) {
            android.util.Log.e("RuStoreOTA", "checkQuiet failed", e);
            return null; // тихо: нет сети/хост недоступен — не мешаем
        }
    }

    /** SHA-256 файла строкой (hex, строчные) — сверка с манифестом. */
    public static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[65536];
        int r;
        try {
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        } finally {
            in.close();
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
