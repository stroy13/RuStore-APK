package ru.rustoredl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Служба переднего плана для пользовательских загрузок: пока качается —
 * в шторке уведомление с прогрессом, паузой и «Отменить»; процесс имеет
 * высокий приоритет и не убивается при сворачивании/смахивании приложения.
 * Автообновление (AutoUpdater) службу не использует — оно тихое по дизайну.
 */
public class DownloadService extends Service {

    static final String CH = "downloads";
    static final String ACTION_CANCEL = "ru.rustoredl.dl.CANCEL";
    static final String ACTION_PAUSE  = "ru.rustoredl.dl.PAUSE";
    static final int NOTIF_ID = 11, DONE_ID = 12;

    /** Живой экран, которому дублируется прогресс (null — экран закрыт). */
    static volatile MainActivity.DlUi sActivityUi;

    /** Время старта текущей загрузки: метка уведомления фиксирована,
     *  иначе шторка при каждом обновлении поднимает его наверх —
     *  и оно «прыгает» с чужими уведомлениями. */
    long startedAt;

    String name = "";
    int curPct;
    long curDone, curTotal;

    @Override public IBinder onBind(Intent i) { return null; }

    static boolean busy() {
        return MainActivity.dlActive && MainActivity.dlThread != null
                && MainActivity.dlThread.isAlive();
    }

    /** Запуск пользовательской загрузки под службой. */
    public static void start(Context ctx, String pkg, String name, long appId,
                             String ver, boolean saveCopy, MainActivity.DlUi ui) {
        if (busy()) {
            if (ui != null) ui.finished("Уже идёт другая загрузка — подожди её окончания", false);
            return;
        }
        sActivityUi = ui;
        ctx.startForegroundService(new Intent(ctx, DownloadService.class)
                .putExtra("pkg", pkg).putExtra("name", name).putExtra("appId", appId)
                .putExtra("ver", ver).putExtra("saveCopy", saveCopy)
                .putExtra("threads", ui != null ? ui.threads() : 8));
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i == null) { stopSelf(); return START_NOT_STICKY; }
        final NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        ensureChannel();

        if (ACTION_CANCEL.equals(i.getAction())) { MainActivity.onCancelDownload(); return START_NOT_STICKY; }

        // Пауза/продолжение из шторки: переключаем и сразу перерисовываем уведомление
        if (ACTION_PAUSE.equals(i.getAction())) {
            if (busy()) {
                MainActivity.togglePauseDownload();
                MainActivity m = MainActivity.instance;
                if (m != null) m.ui.post(m::syncPauseUi);
                nm.notify(NOTIF_ID, progressNotif());
            }
            return START_NOT_STICKY;
        }

        if (busy()) { // страховка: вторая загрузка параллельно невозможна
            MainActivity.DlUi a = sActivityUi; sActivityUi = null;
            if (a != null) a.finished("Уже идёт другая загрузка — подожди её окончания", false);
            stopSelf();
            return START_NOT_STICKY;
        }

        final String pkg = i.getStringExtra("pkg");
        name = i.getStringExtra("name");
        final long appIdN = i.getLongExtra("appId", 0);
        final String ver = i.getStringExtra("ver");
        final boolean saveCopy = i.getBooleanExtra("saveCopy", false);
        final int threads = i.getIntExtra("threads", 8);
        startedAt = System.currentTimeMillis();
        curPct = 0; curDone = 0; curTotal = -1;

        Notification first = bar().setContentText("Получаю ссылку…").setProgress(0, 0, true).build();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIF_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else
            startForeground(NOTIF_ID, first);

        MainActivity.performDownload(this, pkg, name, appIdN, ver, saveCopy, new MainActivity.DlUi() {
            long lastN = 0;

            @Override public void status(String s) {
                MainActivity.DlUi a = sActivityUi;
                if (a != null) a.status(s);
                nm.notify(NOTIF_ID, bar().setContentText(s).setProgress(0, 0, true).build());
            }

            @Override public void progress(boolean indet, int pct, long done, long total) {
                MainActivity.DlUi a = sActivityUi;
                if (a != null) a.progress(indet, pct, done, total);
                curPct = pct; curDone = done; curTotal = total;
                long now = System.currentTimeMillis();
                if (now - lastN < 700) return; // шторку обновляем не чаще ~1.4 раза/с
                lastN = now;
                nm.notify(NOTIF_ID, progressNotif());
            }

            @Override public void finished(String msg, boolean ok) {
                MainActivity.DlUi a = sActivityUi;
                sActivityUi = null;
                if (a != null) a.finished(msg, ok);
                boolean cancelled = msg != null && (msg.toLowerCase().contains("отменен") || msg.toLowerCase().contains("отменён"));
                if (!cancelled) {
                    int doneId = pkg != null ? Math.abs(pkg.hashCode()) : DONE_ID;
                    // не пересекаться с NOTIF_ID=11
                    if (doneId == NOTIF_ID) doneId = DONE_ID + 1;
                    nm.notify(doneId, new Notification.Builder(DownloadService.this, CH)
                            .setSmallIcon(ok ? android.R.drawable.stat_sys_download_done
                                             : android.R.drawable.stat_notify_error)
                            .setContentTitle(ok ? "Загрузка завершена" : "Загрузка прервана")
                            .setContentText((name != null ? name + ": " : "") + msg)
                            .setStyle(new Notification.BigTextStyle().bigText(msg))
                            .setAutoCancel(true)
                            .setContentIntent(PendingIntent.getActivity(DownloadService.this, 3,
                                    getPackageManager().getLaunchIntentForPackage(getPackageName()),
                                    PendingIntent.FLAG_IMMUTABLE))
                            .build());
                }
                stopForeground(true);
                stopSelf();
            }

            @Override public int threads() { return threads; }
        });
        return START_NOT_STICKY;
    }

    /** Уведомление прогресса по последним цифрам; учитывает паузу. */
    private Notification progressNotif() {
        boolean paused = MainActivity.dlPaused;
        Notification.Builder b = bar();
        if (paused) {
            b.setContentText("Пауза  ·  " + (curTotal > 0
                    ? curPct + "%  ·  " + MainActivity.human(curDone) + " из " + MainActivity.human(curTotal)
                    : MainActivity.human(curDone)))
             .setProgress(0, 0, true);
        } else if (curTotal > 0) {
            b.setContentText(curPct + "%  ·  " + MainActivity.human(curDone) + " из " + MainActivity.human(curTotal))
             .setProgress(100, curPct, false);
        } else {
            b.setContentText(MainActivity.human(curDone)).setProgress(0, 0, true);
        }
        return b.build();
    }

    /** Каркас уведомления: заголовок, «Пауза/Продолжить», «Отменить», тап открывает приложение. */
    private Notification.Builder bar() {
        boolean paused = MainActivity.dlPaused;
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(name)
                .setWhen(startedAt)
                .setShowWhen(false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, paused ? "▶  Продолжить" : "❚❚  Пауза",
                        PendingIntent.getService(this, 4,
                                new Intent(this, DownloadService.class).setAction(ACTION_PAUSE),
                                PendingIntent.FLAG_IMMUTABLE))
                .addAction(0, "Отменить",
                        PendingIntent.getService(this, 2,
                                new Intent(this, DownloadService.class).setAction(ACTION_CANCEL),
                                PendingIntent.FLAG_IMMUTABLE))
                .setContentIntent(PendingIntent.getActivity(this, 3,
                        getPackageManager().getLaunchIntentForPackage(getPackageName()),
                        PendingIntent.FLAG_IMMUTABLE));
    }

    private void ensureChannel() {
        NotificationChannel ch = new NotificationChannel(CH, "Загрузки",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Прогресс скачивания приложений");
        ch.setShowBadge(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }
}
