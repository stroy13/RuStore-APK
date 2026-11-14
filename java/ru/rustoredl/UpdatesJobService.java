package ru.rustoredl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Фоновая периодическая проверка обновлений (системный JobScheduler,
 * без сторонних библиотек). Найдены обновления → уведомление;
 * включено автообновление → скачивание и установка той же подписи.
 */
public class UpdatesJobService extends JobService {

    static final int JOB_ID = 4242;
    static final int OTA_JOB_ID = 4243;
    static final String CHANNEL = "updates";

    @Override
    public boolean onStartJob(final JobParameters params) {
        // чужой/устаревший id (например, прежняя ежедневная OTA-задача) — гасим сразу
        if (params.getJobId() != JOB_ID) { jobFinished(params, false); return false; }
        if (AutoUpdater.isRunning()) { jobFinished(params, false); return false; }
        final SharedPreferences p = getSharedPreferences("cfg", MODE_PRIVATE);
        final boolean install = p.getBoolean("autoUpdates", false);
        new Thread(() -> {
            AutoUpdater.Res r = AutoUpdater.run(getApplicationContext(), s -> {}, install);
            int remaining = Math.max(0, r.found - r.installed);
            if (r.installed > 0) {
                notifyUser("Автообновление выполнено",
                        "Установлено обновлений: " + r.installed
                                + (remaining > 0 ? " · ещё доступно: " + remaining : ""));
            } else if (r.found > 0) {
                notifyUser("Доступно обновлений: " + r.found,
                        r.names.isEmpty() ? "Открой раздел «Обновления»" : r.names);
            }
            jobFinished(params, false);
        }).start();
        return true; // работа идёт асинхронно
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false; // не требуется перезапуск по расписанию
    }

    // ---------------------------------------------------------- расписание

    /** (Пере)создать задание по текущим настройкам; выключено → отменить. */
    static void schedule(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!p.getBoolean("bgCheck", false)) {
            js.cancel(JOB_ID);
            return;
        }
        long h = Math.max(1, p.getInt("bgIntervalH", 6));
        JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                new ComponentName(ctx, UpdatesJobService.class))
                .setPeriodic(java.util.concurrent.TimeUnit.HOURS.toMillis(h))
                .setPersisted(true) // переживает перезагрузку
                .setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(p.getBoolean("bgWifiOnly", true)
                        ? JobInfo.NETWORK_TYPE_UNMETERED
                        : JobInfo.NETWORK_TYPE_ANY);
        js.schedule(b.build());
        ensureChannel(ctx);
    }

    /** Уведомления OTA убраны (оповещение — диалог при запуске); задачу гасим. */
    static void cancelOta(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(OTA_JOB_ID);
    }

    // ---------------------------------------------------------- уведомления

    private void notifyUser(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent in = new Intent(this, UpdatesActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 0, in,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(JOB_ID, n);
        } catch (Exception ignore) {
            // нет разрешения на уведомления — не беда
        }
    }

    static void ensureChannel(Context ctx) {
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                "Обновления приложений", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Найденные обновления установленных приложений");
        nm.createNotificationChannel(ch);
    }
}
