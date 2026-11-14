package ru.rustoredl;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Обёртка над API RuStore: общая для всех экранов.
 * Содержит автоподбор кода версии клиента (417/419/400 → подбор → сохранение).
 */
public final class RuStoreApi {

    static final String DEFAULT_VERCODE = "110802";
    private static final String API = "https://backapi.rustore.ru/applicationData";

    /** Колбэк прогресс-сообщений (может быть null). */
    public interface Status { void on(String s); }

    /** Приложения нет в каталоге RuStore (это не ошибка). */
    public static class NotFound extends Exception {
        NotFound(String m) { super(m); }
    }

    private static String verCodeCache;

    private RuStoreApi() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    static synchronized String verCode(Context c) {
        if (verCodeCache == null)
            verCodeCache = prefs(c).getString("verCode", DEFAULT_VERCODE);
        return verCodeCache;
    }

    /**
     * Запрос к API с автоподбором ruStoreVerCode (см. README).
     * 404 бросается как «приложение не найдено».
     */
    public static JSONObject request(Context c, String method, String url,
                                     JSONObject body, Status st) throws Exception {
        int code = -1;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection conn = open(c, method, url, body, verCode(c));
            code = conn.getResponseCode();
            if (code == 400 || code == 417 || code == 419) {
                conn.disconnect();
                if (attempt > 0) throw new Exception("RuStore изменил API (HTTP " + code + ")");
                if (st != null) st.on("RuStore изменил требования — подбираю актуальную версию клиента…");
                String nv = discover(c, method, url, body);
                if (nv == null) throw new Exception("не удалось подобрать код версии клиента RuStore");
                verCodeCache = nv;
                prefs(c).edit().putString("verCode", nv).apply();
                continue;
            }
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (in != null) copy(in, bo);
            conn.disconnect();
            String s = new String(bo.toByteArray(), "UTF-8");
            if (code == 404) throw new NotFound("приложение не найдено");
            if (code >= 400) throw new Exception("HTTP " + code);
            return new JSONObject(s);
        }
        throw new Exception("HTTP " + code);
    }

    private static HttpURLConnection open(Context c, String method, String url,
                                          JSONObject body, String vc) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        SecurityUtil.applyTo(conn); // TLS-pinning
        conn.setRequestProperty("ruStoreVerCode", vc);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestMethod(method);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream o = conn.getOutputStream();
            o.write(body.toString().getBytes("UTF-8"));
            o.close();
        }
        return conn;
    }

    private static int status(Context c, String method, String url, JSONObject body, String vc) {
        try {
            HttpURLConnection conn = open(c, method, url, body, vc);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Подбор рабочего ruStoreVerCode: 200/404 — принят; 417 — выше; 419 — ниже. */
    private static String discover(Context c, String method, String url, JSONObject body) {
        long lo = 0, hi = 200_000_000L;
        try {
            long cv = Long.parseLong(verCode(c));
            int st = status(c, method, url, body, String.valueOf(cv));
            if (st == 200 || st == 404) return String.valueOf(cv);
            if (st == 417) lo = cv;
            else if (st == 419) hi = cv;
            else { if (st == 400 && url.contains("/overallInfo/")) throw new NotFound("нет в каталоге RuStore"); return null; }

            if (lo > 0) { // слишком старый: удваиваем вверх
                for (int i = 0; i < 30; i++) {
                    long v = lo * 2;
                    if (v >= hi) break;
                    st = status(c, method, url, body, String.valueOf(v));
                    if (st == 200 || st == 404) return String.valueOf(v);
                    if (st == 417) lo = v;
                    else if (st == 419) { hi = v; break; }
                    else { if (st == 400 && url.contains("/overallInfo/")) throw new NotFound("нет в каталоге RuStore"); return null; }
                }
            } else {      // слишком новый: делим вниз
                for (int i = 0; i < 40; i++) {
                    long v = hi / 2;
                    if (v < 1) break;
                    st = status(c, method, url, body, String.valueOf(v));
                    if (st == 200 || st == 404) return String.valueOf(v);
                    if (st == 419) hi = v;
                    else if (st == 417) { lo = v; break; }
                    else { if (st == 400 && url.contains("/overallInfo/")) throw new NotFound("нет в каталоге RuStore"); return null; }
                }
            }
            int steps = 0;
            while (hi - lo > 1 && steps++ < 40) {
                long mid = lo + (hi - lo) / 2;
                st = status(c, method, url, body, String.valueOf(mid));
                if (st == 200 || st == 404) return String.valueOf(mid);
                if (st == 417) lo = mid;
                else if (st == 419) hi = mid;
                else { if (st == 400 && url.contains("/overallInfo/")) throw new NotFound("нет в каталоге RuStore"); return null; }
            }
        } catch (Exception ignore) {}
        return null;
    }

    static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
    }
}
