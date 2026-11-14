package ru.rustoredl;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.widget.Toast;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Автообновление: находит обновления установленных приложений в RuStore
 * и (по настройке) скачивает и запускает установку — только при совпадении
 * подписи. Работает из MainActivity (при запуске) и из фонового задания
 * (UpdatesJobService).
 */
public final class AutoUpdater {

    /** Колбэк статусных сообщений (UI-поток). */
    public interface Log { void on(String s); }

    /** Итог прохода. */
    static final class Res {
        int installed;        // запущено установок
        int failed;           // ошибки скачивания
        int found;            // всего приложений с новой версией в витрине
        String names = "";    // первые найденные (для уведомления)
    }

    static final class Cand { String pkg, name, ver; long appId; }

    private static volatile boolean running = false;

    private AutoUpdater() {}

    public static boolean isRunning() { return running; }

    /** Запуск из MainActivity: через паузу, чтобы UI успел подняться. */
    public static void maybeRun(final Activity a) {
        android.content.SharedPreferences p = a.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        if (running) return;
        if (!p.getBoolean("licenseOk", false)) return;   // до принятия лицензии — нет
        if (!p.getBoolean("autoUpdates", false)) return; // выключено в настройках
        final WeakReference<Activity> ref = new WeakReference<>(a);
        a.findViewById(android.R.id.content).postDelayed(() -> {
            Activity act = ref.get();
            if (act == null || act.isFinishing() || act.isDestroyed()) return;
            final Context c = act.getApplicationContext();
            // ВНИМАНИЕ: run() синхронный и долгий (проверка всех приложений) —
            // выполнять только в фоновом потоке, иначе ANR главного потока
            new Thread(() -> {
                final Res r = run(c, activityLog(ref), true);
                if (r.installed + r.failed > 0)
                    toast(c, "Автообновление: запущена установка " + r.installed
                            + (r.failed > 0 ? ", ошибки: " + r.failed : ""));
                else if (r.found == 0)
                    toast(c, "Автообновление: всё уже актуально");
            }, "autoupdate").start();
        }, 2000);
    }

    /** Лог на главный экран (TextView статуса), переживает закрытие Activity. */
    private static Log activityLog(final WeakReference<Activity> ref) {
        return s -> {
            Activity x = ref.get();
            if (x == null || x.isFinishing() || x.isDestroyed()) return;
            x.runOnUiThread(() -> {
                try {
                    android.widget.TextView st = x.findViewById(R.id.status);
                    if (st != null) st.setText(s);
                } catch (Exception ignore) {}
            });
        };
    }

    /**
     * Полный проход по установленным приложениям.
     * @param install true — скачать и запустить установку совпадающих по подписи;
     *                false — только посчитать найденные обновления.
     */
    static Res run(final Context ctx, final Log log, final boolean install) {
        Res res = new Res();
        if (running) return res;
        running = true;
        try {
            List<PackageInfo> mine = new ArrayList<>();
            try {
                PackageManager pm = ctx.getPackageManager();
                for (PackageInfo pi : pm.getInstalledPackages(0)) {
                    ApplicationInfo ai = pi.applicationInfo;
                    if (ai == null) continue;
                    if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                    if (ctx.getPackageName().equals(pi.packageName)) continue;
                    if (pi.packageName.startsWith("com.android.")
                            || pi.packageName.startsWith("com.google.")) continue;
                    if (Exclusions.isExcluded(ctx, pi.packageName)) continue; // список исключений
                    if (Exclusions.isHidden(ctx, pi.packageName)) continue; // скрытые из списка — не автообновляем тоже
                    mine.add(pi);
                }
            } catch (Exception e) {
                return res;
            }
            if (mine.isEmpty()) return res;

            // ---- параллельная проверка витрины (адаптивно по настройке dlThreads, 4-8 потоков)
            final List<String> names = Collections.synchronizedList(new ArrayList<>());
            final List<Cand> todo = Collections.synchronizedList(new ArrayList<>());
            final AtomicInteger done = new AtomicInteger();
            final int total = mine.size();
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(4, Math.min(8, ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getInt("dlThreads", 8))));
            for (final PackageInfo pi : mine) {
                pool.submit(() -> {
                    try { check(ctx, pi, todo, names); } catch (Exception ignore) {
                        // ошибки проверки отдельных приложений просто пропускаем
                    }
                    log.on("Автообновление: проверяю " + done.incrementAndGet()
                            + " из " + total + "…");
                });
            }
            pool.shutdown();
            try { pool.awaitTermination(15, java.util.concurrent.TimeUnit.MINUTES); }
            catch (InterruptedException e) { return res; }

            res.found = names.size();
            res.names = join(names);
            log.on("Автообновление: найдено обновлений — " + res.found);
            if (!install) return res;

            // ---- последовательная установка: система просит подтвердить каждую
            for (final Cand c : todo) {
                if (MainActivity.dlActive) break; // пользователь начал своё — стоп
                final CountDownLatch latch = new CountDownLatch(1);
                final boolean[] ok = {false};
                log.on("Автообновление: скачиваю " + c.name + "…");
                MainActivity.performDownload(ctx, c.pkg, c.name, c.appId, c.ver, false,
                        new MainActivity.DlUi() {
                            @Override public void status(String s) { }
                            @Override public void progress(boolean indet, int pct, long d, long t) {
                                log.on("Автообновление " + c.name + ": "
                                        + (indet ? "скачиваю…" : pct + "%"));
                            }
                            @Override public void finished(String msg, boolean success) {
                                ok[0] = success;
                                latch.countDown();
                            }
                        });
                try { latch.await(); } catch (InterruptedException e) { break; }
                if (ok[0]) res.installed++; else res.failed++;
            }
        } finally {
            running = false;
        }
        return res;
    }

    private static String join(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size() && i < 3; i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.get(i));
        }
        if (names.size() > 3) sb.append(" и ещё ").append(names.size() - 3);
        return sb.toString();
    }

    /**
     * Кандидат: новая версия в витрине. Совпадающая подпись → todo,
     * любая новая версия → names (для счётчика/уведомления).
     */
    private static void check(Context ctx, PackageInfo pi,
                              List<Cand> todo, List<String> names) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        long instCode = UpdatesActivity.vc(pi);
        JSONObject r = RuStoreApi.request(ctx, "GET",
                MainActivity.API + "/overallInfo/" + pi.packageName, null, null);
        JSONObject body = r.getJSONObject("body");
        long newCode = body.optLong("versionCode", 0);
        if (newCode <= 0 || newCode <= instCode) return;

        String newVer = body.optString("versionName", "");
        String name = body.optString("appName", "");
        if (name.isEmpty()) name = UpdatesActivity.labelOf(pm, pi);
        names.add(name);

        // только совпадающая подпись — иначе установка не пройдёт
        String mine = UpdatesActivity.installedSig(pm, pi.packageName);
        if (mine != null) {
            boolean same = false;
            try {
                org.json.JSONArray sigs = body.getJSONArray("signatures");
                for (int i = 0; i < sigs.length(); i++)
                    if (mine.equalsIgnoreCase(sigs.getString(i))) { same = true; break; }
            } catch (Exception ignore) {}
            if (!same) return;
        }

        // витрина не должна требовать более новый Android, чем стоит
        if (body.optInt("minSdkVersion", 0) > android.os.Build.VERSION.SDK_INT) return;

        Cand c = new Cand();
        c.pkg = pi.packageName;
        c.name = name;
        c.ver = newVer;
        c.appId = body.optLong("appId", 0);
        if (c.appId != 0) todo.add(c);
    }

    private static void toast(final Context c, final String s) {
        try {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> Toast.makeText(c, s, Toast.LENGTH_LONG).show());
        } catch (Exception ignore) {}
    }
}
