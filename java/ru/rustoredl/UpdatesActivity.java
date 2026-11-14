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
import java.io.InputStream;
import java.net.HttpURLConnection;
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
        File partFile;               // ".part" текущего скачивания (для отмены)
        boolean cancelRequested;      // не показывать отмену как ошибку после callback службы
    }

    static final int ST_IDLE = 0, ST_DOWNLOADING = 1, ST_DONE = 2, ST_ERROR = 3;

    private final Handler ui = new Handler();
    private final List<Info> data = new ArrayList<>();
    private MyAdapter adapter;
    private TextView status;
    private Button checkBtn;
    private LinearLayout progressBox;
    private ProgressBar progressBar;
    private TextView progressText;
    private volatile boolean scanning;
    private final java.util.ArrayList<String> failed = new java.util.ArrayList<>();
    private ExecutorService pool;
    private final Map<String, Info> active = new HashMap<>(); // pkg → идёт загрузка

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_updates);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        status = findViewById(R.id.status);
        status.setOnClickListener(v -> showFailed());
        checkBtn = findViewById(R.id.checkBtn);
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
        scanning = true;
        data.clear();
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
        if (in.state == ST_DOWNLOADING || active.containsKey(in.pkg)) return;
        if (!MainActivity.ensureInstallPermission(this)) return;

        // версия уже скачана и файлы живы — предложим установить, а не качать заново
        JSONObject already = HistoryActivity.findRecord(this, in.pkg, in.newV);
        if (already != null) { offerAlready(in, already); return; }
        startUpdate(in);
    }

    /** Эта версия уже в истории — установить сохранённое или скачать заново? */
    private void offerAlready(final Info in, JSONObject rec) {
        final List<File> fs = HistoryActivity.recordFiles(this, rec);
        new android.app.AlertDialog.Builder(this)
                .setTitle(in.name)
                .setMessage("Версия " + in.newV + " уже скачана ("
                        + MainActivity.human(HistoryActivity.filesSize(fs))
                        + " в кэше). Установить сохранённый файл или скачать заново?")
                .setPositiveButton("Установить", (d, w) ->
                        HistoryActivity.installFiles(this, fs, rec))
                .setNegativeButton("Скачать заново", (d, w) -> startUpdate(in))
                .setNeutralButton("Отмена", null)
                .show();
    }

    private void startUpdate(final Info in) {
        in.state = ST_DOWNLOADING;
        in.pct = 0;
        in.cancelRequested = false;
        in.msg = "Получаю ссылку…";
        in.partFile = new File(getCacheDir(), "upd_" + in.pkg.hashCode() + ".zip.part");
        active.put(in.pkg, in);
        adapter.notifyDataSetChanged();

        final boolean saveCopy = getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .getBoolean("saveCopy", false);
        DownloadService.start(this, in.pkg, in.name, in.appId, in.newV, saveCopy,
                new MainActivity.DlUi() {
                    @Override public void status(String s) { in.msg = s; ui.post(adapter::notifyDataSetChanged); }
                    @Override public void progress(boolean indet, int pct, long done, long total) {
                        in.pct = indet ? -1 : Math.max(0, Math.min(100, pct));
                        in.msg = indet
                                ? "Скачиваю… " + MainActivity.human(done)
                                : "Скачиваю… " + pct + "% из " + MainActivity.human(total);
                        ui.post(adapter::notifyDataSetChanged);
                    }
                    @Override public int threads() {
                        return Math.max(1, Math.min(16,
                                getSharedPreferences("cfg", Context.MODE_PRIVATE).getInt("dlThreads", 8)));
                    }
                    @Override public void finished(String msg, boolean ok) {
                        active.remove(in.pkg);
                        boolean cancelled = (msg != null
                                && msg.toLowerCase(java.util.Locale.ROOT).contains("отмен"))
                                || (in.cancelRequested && !ok);
                        in.cancelRequested = false;
                        if (cancelled) {
                            // Отмена — не ошибка и не красный статус; сбрасываем строку.
                            in.state = ST_IDLE;
                            in.pct = 0;
                            in.msg = null;
                            if (in.partFile != null) in.partFile.delete();
                        } else {
                            in.state = ok ? ST_DONE : ST_ERROR;
                            in.msg = msg;
                            if (!ok && in.partFile != null) in.partFile.delete();
                        }
                        ui.post(adapter::notifyDataSetChanged);
                    }
                });
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
                btn.setOnClickListener(bv -> {
                    in.cancelRequested = true;
                    in.state = ST_IDLE;
                    in.msg = null;
                    // Оставляем active до callback: нельзя случайно запустить
                    // вторую загрузку, пока служба завершает отмену первой.
                    MainActivity.onCancelDownload();
                    adapter.notifyDataSetChanged();
                });
            } else {
                pb.setVisibility(View.GONE);
                if (in.state == ST_DONE) {
                    state.setVisibility(View.VISIBLE);
                    state.setTextColor(getResources().getColor(R.color.success));
                    state.setText("Обновлено ✓");
                    btn.setVisibility(View.GONE);
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
