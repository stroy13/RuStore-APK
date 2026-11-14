package ru.rustoredl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

public class InstallReceiver extends BroadcastReceiver {
    static final String TAG = "RuStoreOTA";

    @Override
    public void onReceive(Context ctx, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String statusMsg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.d(TAG, "onReceive status=" + status + " msg=" + statusMsg + " extras=" + i.getExtras());
        String msg;
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            Log.d(TAG, "PENDING_USER_ACTION confirm=" + confirm);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                // HyperOS / Android 14+ может блокировать startActivity из Broadcast
                boolean launched = false;
                try {
                    ctx.startActivity(confirm);
                    launched = true;
                    Log.d(TAG, "startActivity confirm OK");
                } catch (Exception e) {
                    Log.e(TAG, "startActivity failed", e);
                }
                if (!launched) {
                    // fallback — уведомление, тап открывает установщик
                    try { showFallbackNotification(ctx, confirm); }
                    catch (Exception e) { Log.e(TAG, "fallback notify failed", e); }
                    msg = "Нажми на уведомление чтобы подтвердить установку";
                } else {
                    msg = "Подтверди установку на экране";
                }
            } else {
                Log.e(TAG, "confirm is null");
                msg = "Системный установщик не вернул окно — попробуй ещё раз";
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            msg = "✓ Установлено!";
            Log.d(TAG, "INSTALL SUCCESS");
            // автоочистка: удалить APK установленной версии (кэш + копии в Загрузках)
            final String ipkg = i.getStringExtra("pkg"), iver = i.getStringExtra("ver");
            if (ipkg != null && ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                    .getBoolean("autoClean", false)) {
                final Context c = ctx.getApplicationContext();
                HistoryActivity.cleanVersion(c, ipkg, iver);
            }
        } else {
            String m = statusMsg;
            // частые сообщения на HyperOS делаем понятнее
            if (m != null && m.toLowerCase().contains("unknown sources")) m = "Разреши «Установка неизвестных приложений» для RuStoreDL";
            else if (m != null && m.toLowerCase().contains("user blocked")) m = "Установка заблокирована системой — разреши в Настройки → Безопасность";
            msg = "Установка не удалась" + (m == null ? " (код " + status + ")" : ": " + m);
            Log.e(TAG, msg);
            // на фоне — уведомление об ошибке
            try { showErrorNotification(ctx, msg); } catch (Exception ignore) {}
        }
        final String fMsg = msg;
        MainActivity m = MainActivity.instance;
        if (m != null) m.ui.post(() -> { m.busy(false); m.status(fMsg); Toast.makeText(ctx, fMsg, Toast.LENGTH_LONG).show(); });
        else Toast.makeText(ctx, fMsg, Toast.LENGTH_LONG).show(); // запуск из истории
    }

    private void showFallbackNotification(Context ctx, Intent confirm) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String chId = "ota_confirm";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(chId, "Подтверждение установки", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Нажми чтобы открыть установщик системы");
            nm.createNotificationChannel(ch);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        // confirm уже содержит нужный компонент установщика — оборачиваем в PendingIntent
        PendingIntent pi;
        try {
            pi = PendingIntent.getActivity(ctx, 99, confirm, flags);
        } catch (Exception e) {
            // если система не дала getActivity — пробуем через обычный launch
            pi = PendingIntent.getActivity(ctx, 99, ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName()), PendingIntent.FLAG_IMMUTABLE);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, chId)
                : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
         .setContentTitle("Требуется подтверждение")
         .setContentText("Нажми чтобы установить обновление")
         .setStyle(new Notification.BigTextStyle().bigText("Система готова к установке обновления RuStoreDL. Нажми на это уведомление чтобы открыть окно установщика. Если не открывается — Настройки → Приложения → RuStore → «Всплывающие окна» → Разрешить."))
         .setAutoCancel(true)
         .setOngoing(false)
         .setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= 16) b.setPriority(Notification.PRIORITY_HIGH);
        nm.notify(4243, b.build());
    }

    private void showErrorNotification(Context ctx, String err) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        String chId = "ota_confirm";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(chId, "Подтверждение установки", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        PendingIntent pi = open == null ? null : PendingIntent.getActivity(ctx, 100, open, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, chId) : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.stat_notify_error)
         .setContentTitle("Установка не удалась")
         .setContentText(err)
         .setStyle(new Notification.BigTextStyle().bigText(err))
         .setAutoCancel(true);
        if (pi != null) b.setContentIntent(pi);
        nm.notify(4244, b.build());
    }
}
