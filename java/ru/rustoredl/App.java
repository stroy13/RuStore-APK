package ru.rustoredl;

import android.app.Application;
import android.content.Context;

/**
 * Прогрев при старте процесса: SharedPreferences и PackageManager
 * читаются в фоне, чтобы главный поток на холодном старте не ждал
 * дискового ввода-вывода (профилактика ANR под нагрузкой системы).
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        final Context c = this;
        new Thread(() -> {
            try {
                // первый доступ к prefs загружает и парсит XML — делаем это
                // заранее в фоне, чтобы onCreate Activity не ждал
                c.getSharedPreferences("cfg", Context.MODE_PRIVATE).getAll();
                // прогрев binder-канала PackageManager (версия, подпись)
                c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            } catch (Exception ignore) {}
        }, "app-prefetch").start();
    }
}
