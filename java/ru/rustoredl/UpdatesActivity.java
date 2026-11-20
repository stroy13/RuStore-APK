package ru.rustoredl;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * «Обновления»: показывает установленные на устройстве приложения,
 * для которых в каталоге RuStore есть более новая версия, и обновляет их в один тап.
 */
public class UpdatesActivity extends Activity {

    static class Info {
        String pkg, name, oldV, newV;
        long appId, newCode, size;
        String iconUrl;
        Bitmap icon;                 // подгружается лениво
        int state = ST_IDLE;         // ST_*
        int pct;
        String msg;
        boolean blocked;             // витрина требует более новый Android
        boolean sigMismatch;         // подпись витрины ≠ подпись установленного
        volatile boolean cancelRequested;
        volatile HttpURLConnection connection;
        Thread worker;
        boolean started;
    }

    static final int ST_IDLE = 0, ST_DOWNLOADING = 1, ST_DONE = 2, ST_ERROR = 3;

    private final Handler ui = new Handler();
    private final List<Info> data = new ArrayList<>();
    private MyAdapter adapter;
    private TextView status;
    private Button checkBtn;
    private Button updateAllBtn;
    private LinearLayout progressBox;
    private ProgressBar progressBar;
    private TextView progressText;
    private volatile boolean scanning;
    private final java.util.ArrayList<String> failed = new java.util.ArrayList<>();
    private ExecutorService pool;
    private final Map<String, Info> active = new HashMap<>(); // pkg → в очереди или загружается
    private final List<Info> pendingDownloads = new ArrayList<>();
    private int runningDownloads;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_updates);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        status = findViewById(R.id.status);
        status.setOnClickListener(v -> showFailed());
        checkBtn = findViewById(R.id.checkBtn);
        updateAllBtn = findViewById(R.id.updateAllBtn);
        updateAllBtn.setOnClickListener(v -> updateAll());
        progressBox = findViewById(R.id.progressBox);
        progressBar = findViewById(R.id.progressBar);
        progressText = findViewById(R.id.progressText);
        ListView list = findViewById(R.id.list);
        adapter = new MyAdapter();
        list.setAdapter(adapter);
        // долгий тап: меню — исключить из автообновления / скрыть из списка
        list.setOnItemLongClickListener((parent, view, pos, id) -> {
            final Info in = data.get(pos);
            String label = in.name;
            try {
                label = String.valueOf(getPackageManager().getApplicationLabel(
                        getPackageManager().getApplicationInfo(in.pkg, 0)));
            } catch (Exception ignore) {}
            final String fLabel = label;
            final boolean isExcluded = Exclusions.isExcluded(this, in.pkg);
            final boolean isHidden = Exclusions.isHidden(this, in.pkg);
            String[] items = new String[] {
                    isExcluded ? "Убрать из исключений автообновления" : "Исключить из автообновления",
                    isHidden ? "Показать в списке обновлений" : "Скрыть из списка обновлений"
            };
            new android.app.AlertDialog.Builder(this)
                    .setTitle(fLabel)
                    .setItems(items, (d, which) -> {
                        if (which == 0) {
                            if (isExcluded) {
                                Exclusions.remove(this, in.pkg);
                                Toast.makeText(this, "Возвращено в автообновление", Toast.LENGTH_SHORT).show();
                            } else {
                                Exclusions.add(this, in.pkg);
                                Toast.makeText(this, "Исключено из автообновления", Toast.LENGTH_SHORT).show();
                            }
                            adapter.notifyDataSetChanged();
                        } else if (which == 1) {
                            if (isHidden) {
                                Exclusions.removeHidden(this, in.pkg);
                                Toast.makeText(this, "Возвращено в список: " + fLabel, Toast.LENGTH_SHORT).show();
                                adapter.notifyDataSetChanged();
                            } else {
                                Exclusions.addHidden(this, in.pkg);
                                Toast.makeText(this, "Скрыто из списка: " + fLabel, Toast.LENGTH_SHORT).show();
                                data.remove(in);
                                adapter.notifyDataSetChanged();
                                status("Скрыто: " + fLabel + " — вернёшь в Настройки → «Скрытые из списка обновлений»");
                            }
                        }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            return true;
        });
        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("updHintShown", false)) {
            status("Долгий тап по приложению — исключить из автообновления или скрыть из списка");
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("updHintShown", true).apply();
        }
        checkBtn.setOnClickListener(v -> startScan());
        checkListSupported(); // подсказка про Android 8, если нужно
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        scanning = false;
        if (pool != null) pool.shutdownNow();
        super.onDestroy();
    }

    private void checkListSupported() {
        // всё работает на minSdk 26+; просто дружелюбный первый запуск
        if (firstRun()) status("Нажми «Проверить обновления» — я посмотрю, какие из установленных приложений есть в RuStore в новой версии");
    }

    private boolean firstRun() {
        android.content.SharedPreferences p = getSharedPreferences("cfg", Context.MODE_PRIVATE);
        boolean was = p.getBoolean("updatesHintShown", false);
        p.edit().putBoolean("updatesHintShown", true).apply();
        return !was;
    }

    private void status(String s) { status.setText(s); }

    // ------------------------------------------------------- сканирование

    private void startScan() {
        if (scanning) return;
        if (!active.isEmpty()) {
            Toast.makeText(this, "Дождись завершения текущих загрузок", Toast.LENGTH_SHORT).show();
            return;
        }
        scanning = true;
        data.clear();
        updateAllBtn.setVisibility(View.GONE);
        synchronized (failed) { failed.clear(); }
        adapter.notifyDataSetChanged();
        checkBtn.setEnabled(false);
        checkBtn.setText("Проверяю…");
        progressBox.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressText.setText("Собираю список установленных приложений…");

        new Thread(() -> {
            try {
                PackageManager pm = getPackageManager();
                List<PackageInfo> apps = pm.getInstalledPackages(0);
                List<PackageInfo> mine = new ArrayList<>();
                for (PackageInfo pi : apps) {
                    ApplicationInfo ai = pi.applicationInfo;
                    if (ai == null) continue;
                    if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue; // системные — мимо
                    if (getPackageName().equals(pi.packageName)) continue;       // себя не предлагаем
                    if (pi.packageName.startsWith("com.android.") || pi.packageName.startsWith("com.google.")) continue;
                    mine.add(pi);
                }
                Collections.sort(mine, (a, b2) -> String.valueOf(
                        a.applicationInfo.loadLabel(pm)).compareToIgnoreCase(
                        String.valueOf(b2.applicationInfo.loadLabel(pm))));

                final int total = mine.size();
                if (total == 0) {
                    ui.post(() -> scanDone(0, 0, 0, 0));
                    return;
                }
                ui.post(() -> { progressText.setText("Проверяю 0 из " + total + "…"); progressBar.setIndeterminate(false); });

                AtomicInteger done = new AtomicInteger();
                AtomicInteger errors = new AtomicInteger();
                AtomicInteger missing = new AtomicInteger();
                AtomicInteger hiddenSig = new AtomicInteger();
                AtomicInteger hiddenUser = new AtomicInteger();
                // адаптивный пул: берём значение из ползунка «Скорость загрузки» (1-16), но для проверки достаточно 4-8
                int netThreads = Math.max(4, Math.min(8, getSharedPreferences("cfg", MODE_PRIVATE).getInt("dlThreads", 8)));
                if (pool != null) pool.shutdownNow();
                pool = Executors.newFixedThreadPool(netThreads);
                for (PackageInfo pi : mine) {
                    pool.submit(() -> {
                        if (!scanning) return;
                        try {
                            checkOneRetry(pm, pi);
                        } catch (RuStoreApi.NotFound nf) {
                            missing.incrementAndGet(); // просто нет в витрине
                        } catch (HiddenUser hu) {
                            hiddenUser.incrementAndGet(); // скрыто пользователем
                        } catch (Hidden h) {
                            hiddenSig.incrementAndGet(); // скрыт по настройке подписи
                        } catch (Exception e) {
                            errors.incrementAndGet();
                            synchronized (failed) {
                                failed.add(labelOf(pm, pi) + " — " + humanize(e));
                            }
                        }
                        int d = done.incrementAndGet();
                        ui.post(() -> progressText.setText("Проверяю " + d + " из " + total + "…"));
                        if (d == total) {
                            final int err = errors.get(), miss = missing.get(), hid = hiddenSig.get(), hu = hiddenUser.get();
                            ui.post(() -> scanDone(total, err, miss, hid + hu));
                        }
                    });
                }
            } catch (Exception e) {
                ui.post(() -> {
                    scanning = false;
                    checkBtn.setEnabled(true);
                    checkBtn.setText("Проверить обновления");
                    progressBox.setVisibility(View.GONE);
                    status("Ошибка: " + e.getMessage());
                });
            }
        }).start();
    }

    /** SHA-256 (hex) подписи установленного пакета — для сверки с витриной. */
    static String installedSig(PackageManager pm, String pkg) {
        try {
            android.content.pm.Signature[] sigs;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.content.pm.SigningInfo si = pm.getPackageInfo(pkg,
                        PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
                if (si == null) return null;
                sigs = si.hasMultipleSigners() ? si.getSigningCertificateHistory()
                        : si.getApkContentsSigners();
            } else {
                sigs = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
            }
            if (sigs == null || sigs.length == 0) return null;
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
            StringBuilder h = new StringBuilder(d.length * 2);
            for (byte x : d) h.append(String.format("%02x", x));
            return h.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static String minSdkVersionName(int api) {
        if (api >= 36) return "16"; if (api >= 35) return "15"; if (api >= 34) return "14";
        if (api >= 33) return "13"; if (api >= 32) return "12L"; if (api >= 31) return "12";
        if (api >= 30) return "11"; if (api >= 29) return "10"; if (api >= 28) return "9";
        if (api >= 27) return "8.1"; if (api >= 26) return "8.0";
        return String.valueOf(api);
    }

    /** checkOne с выдержкой при 429/503 («не спеши») и сетевых сбоях — до 3 попыток. */
    private void checkOneRetry(PackageManager pm, PackageInfo pi) throws Exception {
        int attempt = 0;
        while (true) {
            try {
                checkOne(pm, pi);
                return;
            } catch (RuStoreApi.NotFound nf) {
                throw nf;
            } catch (Exception e) {
                attempt++;
                String m = String.valueOf(e.getMessage());
                boolean rate = m.contains("429") || m.contains("503");
                boolean net = m.toLowerCase().contains("timeout")
                        || m.toLowerCase().contains("timed out")
                        || m.contains("reset") || m.contains("EOF")
                        || m.contains("Unable to resolve host");
                if (!scanning || attempt >= 3 || !(rate || net)) throw e;
                try {
                    Thread.sleep(rate ? 900L * (1L << (attempt - 1)) : 600L * attempt);
                } catch (InterruptedException ie) { throw e; }
            }
        }
    }

    /** Приложение найдено, но скрыто настройкой «другая подпись». */
    static final class Hidden extends Exception {}
    /** Приложение скрыто пользователем из списка обновлений. */
    static final class HiddenUser extends Exception {}

    static String labelOf(PackageManager pm, PackageInfo pi) {
        try { return String.valueOf(pi.applicationInfo.loadLabel(pm)); }
        catch (Exception e) { return pi.packageName; }
    }

    /** Понятная причина сбоя для списка. */
    static String humanize(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        String low = m.toLowerCase();
        if (low.contains("timeout") || low.contains("timed out")) return "таймаут — медленная сеть";
        if (low.contains("http 429") || low.contains("http 503")) return "сервер просит не спешить, попробуй позже";
        if (low.contains("http 5")) return "сервер RuStore сбойнул";
        if (low.contains("eof") || low.contains("reset") || low.contains("broken")) return "соединение оборвалось";
        return m;
    }

    private void showFailed() {
        java.util.ArrayList<String> f;
        synchronized (failed) { f = new java.util.ArrayList<>(failed); }
        if (f.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String x : f) sb.append("• ").append(x).append("\n");
        new android.app.AlertDialog.Builder(this)
                .setTitle("Не удалось проверить (" + f.size() + ")")
                .setMessage(sb.toString().trim())
                .setPositiveButton("Понятно", null)
                .show();
    }

    static long vc(PackageInfo pi) {
        return android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    private void checkOne(PackageManager pm, PackageInfo pi) throws Exception {
        if (Exclusions.isHidden(this, pi.packageName)) throw new HiddenUser();
        long instCode = vc(pi);
        JSONObject r = RuStoreApi.request(this, "GET",
                MainActivity.API + "/overallInfo/" + pi.packageName, null, null);
        JSONObject body = r.getJSONObject("body");
        long newCode = body.optLong("versionCode", 0);
        String newName = body.optString("appName", "");
        String newVer = body.optString("versionName", "");
        long size = body.optLong("fileSize", 0);
        String iconUrl = body.optString("iconUrl", "");
        int minSdk = body.optInt("minSdkVersion", 0);

        // обновление = витринный versionCode выше установленного;
        // если коды недоступны — сравнение versionName по сегментам
        boolean newer;
        if (newCode > 0 && instCode > 0) newer = newCode > instCode;
        else newer = !TextUtils.isEmpty(newVer) && !TextUtils.isEmpty(pi.versionName)
                && cmpVersion(newVer, pi.versionName) > 0;
        if (!newer) return;

        // витрина требует более новый Android, чем стоит на устройстве
        if (minSdk > android.os.Build.VERSION.SDK_INT) {
            Info bl = new Info();
            bl.pkg = pi.packageName;
            bl.name = TextUtils.isEmpty(newName)
                    ? String.valueOf(pi.applicationInfo.loadLabel(pm)) : newName;
            bl.oldV = pi.versionName == null ? "?" : pi.versionName;
            bl.newV = newVer;
            bl.size = size;
            bl.blocked = true;
            bl.msg = "Витрина требует Android " + minSdkVersionName(minSdk) + "+";
            synchronized (data) { data.add(bl); }
            ui.post(adapter::notifyDataSetChanged);
            return;
        }

        Info in = new Info();
        in.pkg = pi.packageName;
        in.name = TextUtils.isEmpty(newName) ? String.valueOf(pi.applicationInfo.loadLabel(pm)) : newName;
        in.oldV = pi.versionName == null ? "?" : pi.versionName;
        in.newV = newVer;
        in.appId = body.optLong("appId", 0);
        in.newCode = newCode;
        in.size = size;
        in.iconUrl = iconUrl;
        String mine = installedSig(pm, pi.packageName);
        if (mine != null) {
            boolean ok = false;
            try {
                org.json.JSONArray sigs = body.getJSONArray("signatures");
                for (int i = 0; i < sigs.length(); i++)
                    if (mine.equalsIgnoreCase(sigs.getString(i))) { ok = true; break; }
            } catch (Exception ignore) {}
            in.sigMismatch = !ok;
            if (in.sigMismatch && !getSharedPreferences("cfg", Context.MODE_PRIVATE)
                    .getBoolean("updatesShowSig", true)) {
                throw new Hidden(); // настройка: не показывать такие
            }
        }
        synchronized (data) { data.add(in); }
        ui.post(adapter::notifyDataSetChanged);
    }

    private void scanDone(int total, int errors, int missing, int hiddenSig) {
        scanning = false;
        progressBox.setVisibility(View.GONE);
        checkBtn.setEnabled(true);
        checkBtn.setText("Проверить снова");
        adapter.notifyDataSetChanged();
        refreshUpdateAll();
        String extra = (missing > 0 ? " · нет в RuStore: " + missing : "")
                + (hiddenSig > 0 ? " · скрыто по подписи: " + hiddenSig : "")
                + (errors > 0 ? " · не удалось проверить: " + errors + " (тапни — покажу причины)" : "");
        if (total == 0) {
            status("Установленных пользовательских приложений не найдено");
        } else if (data.isEmpty()) {
            status("Обновлений нет — проверили " + total + ", все актуальны" + extra);
        } else {
            status("Найдены обновления: " + data.size() + " из " + total + extra);
        }
    }

    /** "1.2.10" vs "1.2.9": по числовым сегментам. 1 если a>b, -1 если a<b, 0 — равны. */
    static int cmpVersion(String a, String b) {
        try {
            String[] as = a.replaceAll("[^0-9.]", "").split("\\.");
            String[] bs = b.replaceAll("[^0-9.]", "").split("\\.");
            int n = Math.max(as.length, bs.length);
            for (int i = 0; i < n; i++) {
                long x = i < as.length && !as[i].isEmpty() ? Long.parseLong(as[i]) : 0;
                long y = i < bs.length && !bs[i].isEmpty() ? Long.parseLong(bs[i]) : 0;
                if (x != y) return x > y ? 1 : -1;
            }
            return 0;
        } catch (Exception e) {
            return a.compareTo(b);
        }
    }

    // ------------------------------------------------------- обновление в тап

    private void updateApp(final Info in) {
        enqueueUpdate(in);
    }

    private void updateAll() {
        List<Info> selected = new ArrayList<>();
        for (Info in : data) {
            if (!in.blocked && in.state != ST_DONE && !active.containsKey(in.pkg)) selected.add(in);
        }
        if (selected.isEmpty()) {
            Toast.makeText(this, "Нет доступных обновлений для загрузки", Toast.LENGTH_SHORT).show();
            return;
        }
        for (Info in : selected) enqueueUpdate(in);
        status("В очереди " + selected.size() + " " + appsWord(selected.size())
                + " · одновременно: " + updateConcurrency());
    }

    private static String appsWord(int n) {
        int m = n % 100;
        if (m >= 11 && m <= 14) return "приложений";
        switch (n % 10) {
            case 1: return "приложение";
            case 2: case 3: case 4: return "приложения";
            default: return "приложений";
        }
    }

    private int updateConcurrency() {
        return Math.max(1, Math.min(8, getSharedPreferences("cfg", MODE_PRIVATE)
                .getInt("updateConcurrency", 3)));
    }

    private void enqueueUpdate(Info in) {
        if (in == null || in.blocked || active.containsKey(in.pkg) || in.state == ST_DONE) return;
        in.state = ST_DOWNLOADING;
        in.pct = 0;
        in.cancelRequested = false;
        in.started = false;
        in.msg = "В очереди…";
        active.put(in.pkg, in);
        pendingDownloads.add(in);
        adapter.notifyDataSetChanged();
        refreshUpdateAll();
        launchQueuedDownloads();
    }

    /** Запускает только заданное настройкой количество независимых загрузок. */
    private void launchQueuedDownloads() {
        int limit = updateConcurrency();
        while (runningDownloads < limit && !pendingDownloads.isEmpty()) {
            Info in = pendingDownloads.remove(0);
            if (in.cancelRequested) continue;
            in.started = true;
            runningDownloads++;
            in.msg = "Получаю ссылку…";
            Thread t = new Thread(() -> downloadUpdate(in), "update-" + in.pkg);
            in.worker = t;
            t.start();
        }
        if (!destroyed) adapter.notifyDataSetChanged();
    }

    private void cancelUpdate(Info in) {
        in.cancelRequested = true;
        pendingDownloads.remove(in);
        HttpURLConnection c = in.connection;
        if (c != null) c.disconnect();
        Thread t = in.worker;
        if (t != null) t.interrupt();
        if (!in.started) {
            active.remove(in.pkg);
            in.state = ST_IDLE;
            in.msg = null;
            in.cancelRequested = false;
            adapter.notifyDataSetChanged();
            refreshUpdateAll();
            launchQueuedDownloads();
        } else {
            in.msg = "Отменяю…";
            adapter.notifyDataSetChanged();
        }
    }

    private void downloadUpdate(Info in) {
        File zip = null;
        File staging = null;
        List<File> apks = new ArrayList<>();
        String result = "";
        boolean ok = false;
        try {
            checkCancelled(in);
            Context ctx = getApplicationContext();
            JSONObject response = RuStoreApi.request(ctx, "POST", MainActivity.API + "/download-link",
                    new JSONObject().put("appId", in.appId).put("firstInstall", true), null);
            String apkUrl = response.getJSONObject("body").getString("apkUrl");
            File work = new File(ctx.getCacheDir(), "update_downloads");
            if (!work.exists() && !work.mkdirs()) throw new IOException("не удалось создать каталог загрузки");
            String key = safeFilePart(in.pkg) + "_" + System.nanoTime();
            zip = new File(work, key + ".zip");
            downloadArchive(in, apkUrl, zip);
            checkCancelled(in);
            staging = new File(work, key);
            if (!staging.mkdirs()) throw new IOException("не удалось подготовить APK");
            List<File> extracted = extractUpdateApks(zip, staging, in);
            if (extracted.isEmpty()) throw new IOException("в архиве нет APK");

            File installDir = new File(ctx.getCacheDir(), "install");
            if (!installDir.exists() && !installDir.mkdirs()) throw new IOException("не удалось создать хранилище APK");
            String prefix = safeFilePart(in.pkg) + "_v" + safeFilePart(in.newV)
                    + "_" + System.currentTimeMillis();
            for (int i = 0; i < extracted.size(); i++) {
                checkCancelled(in);
                File src = extracted.get(i);
                File dst = new File(installDir, prefix + (i == 0 ? "_base.apk" : "_split" + i + ".apk"));
                if (!src.renameTo(dst)) copyFile(src, dst);
                apks.add(dst);
            }

            org.json.JSONArray saved = new org.json.JSONArray();
            if (ctx.getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("saveCopy", false)) {
                try {
                    saved = MainActivity.saveToDownloads(ctx, in.pkg, in.newV, apks);
                } catch (Exception copyError) {
                    android.util.Log.w("RuStoreUpdates", "Could not save a Downloads copy", copyError);
                }
            }
            HistoryActivity.addRecord(ctx, in.pkg, in.name, in.newV, apks, saved);

            boolean canInstall = ctx.getPackageManager().canRequestPackageInstalls();
            if (canInstall) {
                try {
                    MainActivity.install(ctx, apks, in.pkg, in.newV);
                    result = "Скачано · запрос отправлен установщику";
                } catch (Exception installError) {
                    result = "Скачано · APK сохранены; установщик: " + humanize(installError);
                }
            } else {
                result = "Скачано · APK готовы к установке из Истории";
            }
            ok = true;
        } catch (Exception e) {
            if (in.cancelRequested || e instanceof InterruptedException
                    || Thread.currentThread().isInterrupted()) result = "Скачивание отменено";
            else result = "Ошибка: " + humanize(e);
        } finally {
            if (in.connection != null) {
                in.connection.disconnect();
                in.connection = null;
            }
            if (zip != null) zip.delete();
            if (staging != null) deleteTree(staging);
            if (!ok) for (File apk : apks) apk.delete();
            final boolean success = ok;
            final String message = result;
            ui.post(() -> finishUpdate(in, success, message));
        }
    }

    private void downloadArchive(Info in, String url, File out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        in.connection = c;
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept-Encoding", "identity");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
        long total = c.getContentLengthLong();
        long done = 0;
        long lastUpdate = 0;
        try (InputStream input = c.getInputStream(); FileOutputStream output = new FileOutputStream(out)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = input.read(buffer)) != -1) {
                checkCancelled(in);
                output.write(buffer, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastUpdate >= 350) {
                    lastUpdate = now;
                    final long bytes = done, expected = total;
                    ui.post(() -> {
                        if (in.state != ST_DOWNLOADING) return;
                        in.pct = expected > 0 ? (int) Math.min(100, bytes * 100 / expected) : -1;
                        in.msg = expected > 0
                                ? "Скачиваю… " + in.pct + "% · " + MainActivity.human(bytes)
                                : "Скачиваю… " + MainActivity.human(bytes);
                        if (!destroyed) adapter.notifyDataSetChanged();
                    });
                }
            }
            output.getFD().sync();
        } finally {
            c.disconnect();
            in.connection = null;
        }
        if (out.length() == 0) throw new IOException("получен пустой архив");
    }

    private List<File> extractUpdateApks(File archive, File staging, Info in) throws Exception {
        List<File> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                checkCancelled(in);
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) continue;
                File file = new File(staging, out.isEmpty() ? "base.apk" : "split" + out.size() + ".apk");
                try (InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(file)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        checkCancelled(in);
                        output.write(buffer, 0, n);
                    }
                }
                out.add(file);
            }
        }
        return out;
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream input = new FileInputStream(src); FileOutputStream output = new FileOutputStream(dst)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            output.getFD().sync();
        }
    }

    private static void checkCancelled(Info in) throws InterruptedException {
        if (in.cancelRequested || Thread.currentThread().isInterrupted()) throw new InterruptedException("cancelled");
    }

    private static String safeFilePart(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        f.delete();
    }

    private void finishUpdate(Info in, boolean ok, String message) {
        if (in.started) runningDownloads = Math.max(0, runningDownloads - 1);
        in.started = false;
        in.worker = null;
        in.connection = null;
        active.remove(in.pkg);
        boolean cancelled = in.cancelRequested;
        in.cancelRequested = false;
        in.state = cancelled ? ST_IDLE : (ok ? ST_DONE : ST_ERROR);
        in.pct = ok ? 100 : 0;
        in.msg = cancelled ? null : message;
        if (!destroyed) {
            adapter.notifyDataSetChanged();
            refreshUpdateAll();
            int remaining = active.size();
            status(remaining > 0 ? "Загрузки: осталось " + remaining + " · выполняется " + runningDownloads
                    : (ok ? message : "Загрузка завершена: " + message));
        }
        launchQueuedDownloads();
    }

    private void refreshUpdateAll() {
        if (updateAllBtn == null || scanning) return;
        boolean available = false;
        for (Info in : data) {
            if (!in.blocked && in.state != ST_DONE && !active.containsKey(in.pkg)) {
                available = true;
                break;
            }
        }
        updateAllBtn.setVisibility(available ? View.VISIBLE : View.GONE);
        if (available) updateAllBtn.setEnabled(true);
    }

    /** Эта версия уже скачана — повторно открыть системное подтверждение. */
    private void installSaved(Info in) {
        JSONObject rec = HistoryActivity.findRecord(this, in.pkg, in.newV);
        if (rec == null) {
            Toast.makeText(this, "Скачанный APK больше не найден", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!MainActivity.ensureInstallPermission(this)) return;
        HistoryActivity.installFiles(this, HistoryActivity.recordFiles(this, rec), rec);
    }

    // ------------------------------------------------------- адаптер

    private class MyAdapter extends BaseAdapter {
        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int p) { return data.get(p); }
        @Override public long getItemId(int p) { return p; }

        @SuppressLint("SetTextI18n")
        @Override
        public View getView(int p, View cv, ViewGroup parent) {
            View v = cv != null ? cv : LayoutInflater.from(UpdatesActivity.this)
                    .inflate(R.layout.item_update, parent, false);
            final Info in = data.get(p);
            v.setTag(in);

            ImageView icon = v.findViewById(R.id.icon);
            TextView name = v.findViewById(R.id.name);
            TextView sub = v.findViewById(R.id.sub);
            TextView state = v.findViewById(R.id.state);
            ProgressBar pb = v.findViewById(R.id.pb);
            Button btn = v.findViewById(R.id.btn);
            // ListView переиспользует строки: сбрасываем цвет от предыдущего элемента.
            state.setTextColor(getResources().getColor(R.color.accent));
            pb.setMax(100);

            name.setText(in.name + (Exclusions.isExcluded(UpdatesActivity.this, in.pkg) ? "  🔇" : ""));
            String sizes = in.size > 0 ? " · " + MainActivity.human(in.size) : "";
            sub.setText(in.oldV + " → " + (TextUtils.isEmpty(in.newV) ? "?" : in.newV) + sizes);
            loadIcon(in, icon);

            if (in.state == ST_DOWNLOADING) {
                state.setVisibility(View.VISIBLE);
                state.setText(in.msg == null ? "" : in.msg);
                pb.setVisibility(View.VISIBLE);
                if (in.pct < 0) { pb.setIndeterminate(true); }
                else { pb.setIndeterminate(false); pb.setProgress(Math.max(0, Math.min(100, in.pct))); }
                btn.setVisibility(View.VISIBLE);
                btn.setEnabled(true);
                btn.setText("Отмена");
                btn.setOnClickListener(bv -> cancelUpdate(in));
            } else {
                pb.setVisibility(View.GONE);
                if (in.state == ST_DONE) {
                    state.setVisibility(View.VISIBLE);
                    state.setTextColor(getResources().getColor(R.color.success));
                    state.setText(in.msg == null ? "Скачано · APK готовы к установке" : in.msg);
                    btn.setVisibility(View.VISIBLE);
                    btn.setText("Установить");
                    btn.setEnabled(true);
                    btn.setOnClickListener(bv -> installSaved(in));
                } else {
                    btn.setVisibility(in.blocked ? View.GONE : View.VISIBLE);
                    boolean busy = active.containsKey(in.pkg);
                    btn.setText(busy ? "…" : "Обновить");
                    btn.setEnabled(!busy);
                    if (!in.blocked) btn.setOnClickListener(bv -> updateApp(in));
                    if (in.blocked) {
                        state.setVisibility(View.VISIBLE);
                        state.setTextColor(getResources().getColor(R.color.danger));
                        state.setText(in.msg == null ? "" : in.msg);
                    } else if (in.state == ST_ERROR) {
                        state.setVisibility(View.VISIBLE);
                        state.setTextColor(getResources().getColor(R.color.danger));
                        state.setText(in.msg == null ? "Ошибка" : in.msg);
                    } else if (in.sigMismatch) {
                        state.setVisibility(View.VISIBLE);
                        state.setTextColor(getResources().getColor(R.color.danger));
                        state.setText("Другая подпись — может не установиться");
                    } else if (Exclusions.isExcluded(UpdatesActivity.this, in.pkg)) {
                        state.setVisibility(View.VISIBLE);
                        state.setTextColor(getResources().getColor(R.color.textTertiary));
                        state.setText("Исключено из автообновления");
                    } else {
                        state.setVisibility(View.GONE);
                    }
                }
            }
            return v;
        }
    }

    private final android.util.LruCache<String, Bitmap> iconCache = new android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
    };
    private final java.util.Set<String> iconLoading = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private void loadIcon(final Info in, final ImageView iv) {
        Bitmap cached = iconCache.get(in.pkg);
        if (cached != null) { iv.setImageBitmap(cached); return; }
        // сначала ярлык приложения (мгновенно), потом красивая иконка с витрины
        try {
            Drawable d = getPackageManager().getApplicationIcon(in.pkg);
            iv.setImageDrawable(d);
        } catch (Exception ignore) {}
        if (TextUtils.isEmpty(in.iconUrl) || iconLoading.contains(in.pkg)) return;
        iconLoading.add(in.pkg);
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new java.net.URL(in.iconUrl).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                InputStream is = c.getInputStream();
                final Bitmap bmp = BitmapFactory.decodeStream(is);
                is.close();
                c.disconnect();
                if (bmp != null) {
                    iconCache.put(in.pkg, bmp);
                    ui.post(() -> {
                        View child = ((ListView) findViewById(R.id.list)).getChildAt(inRow(in));
                        if (child != null && ((Info) child.getTag()) == in) {
                            ((ImageView) child.findViewById(R.id.icon)).setImageBitmap(bmp);
                        }
                    });
                }
            } catch (Exception ignore) {}
        }).start();
    }

    private int inRow(Info in) { return data.indexOf(in); }
}
