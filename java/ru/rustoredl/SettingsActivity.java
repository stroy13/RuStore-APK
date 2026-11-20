package ru.rustoredl;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Настройки: число параллельных соединений загрузки (1–16).
 * Сохраняется сразу, применяется к следующему скачиванию.
 */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF0B0E13);
        getWindow().setNavigationBarColor(0xFF0B0E13);
        setContentView(R.layout.activity_settings);
        findViewById(R.id.back).setOnClickListener(v -> finish());

        SeekBar seek = findViewById(R.id.threadsSeek);
        TextView val = findViewById(R.id.threadsValue);
        int cur = getSharedPreferences("cfg", MODE_PRIVATE).getInt("dlThreads", 8);
        seek.setProgress(cur - 1);
        val.setText(plural(cur));

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                val.setText(plural(p + 1));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                int v = s.getProgress() + 1;
                getSharedPreferences("cfg", MODE_PRIVATE).edit().putInt("dlThreads", v).apply();
                Toast.makeText(SettingsActivity.this, "Потоков: " + v, Toast.LENGTH_SHORT).show();
            }
        });

        // Обновления: показывать приложения с другой подписью (по умолчанию — да)
        Switch showSig = findViewById(R.id.showSigSwitch);
        showSig.setChecked(getSharedPreferences("cfg", MODE_PRIVATE)
                .getBoolean("updatesShowSig", true));
        showSig.setOnCheckedChangeListener((CompoundButton sw, boolean on) -> {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("updatesShowSig", on).apply();
            Toast.makeText(SettingsActivity.this,
                    on ? "Показываю приложения с другой подписью"
                       : "Скрываю приложения с другой подписью",
                    Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.exclusionsRow).setOnClickListener(v -> Exclusions.showDialog(this));
        View hiddenRow = findViewById(R.id.hiddenRow);
        if (hiddenRow != null) hiddenRow.setOnClickListener(v -> Exclusions.showHiddenDialog(this));

        // Обновления: автообновление при запуске (по умолчанию — нет)
        Switch autoUpd = findViewById(R.id.autoUpdSwitch);
        autoUpd.setChecked(getSharedPreferences("cfg", MODE_PRIVATE)
                .getBoolean("autoUpdates", false));
        autoUpd.setOnCheckedChangeListener((CompoundButton sw2, boolean on) -> {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("autoUpdates", on).apply();
            Toast.makeText(SettingsActivity.this,
                    on ? "Автообновление включено — сработает при запуске"
                       : "Автообновление выключено",
                    Toast.LENGTH_SHORT).show();
        });

        // Обновления: фоновая проверка
        final android.content.SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
        Switch autoClean = findViewById(R.id.autoCleanSwitch);
        autoClean.setChecked(cfg.getBoolean("autoClean", false));
        autoClean.setOnCheckedChangeListener((android.widget.CompoundButton swAC, boolean onAC) ->
                cfg.edit().putBoolean("autoClean", onAC).apply());
        Switch otaSwitch = findViewById(R.id.otaSwitch);
        otaSwitch.setChecked(cfg.getBoolean("otaAuto", true));
        otaSwitch.setOnCheckedChangeListener((android.widget.CompoundButton swO, boolean onO) ->
                cfg.edit().putBoolean("otaAuto", onO).apply());
        Switch bgSwitch = findViewById(R.id.bgSwitch);
        View bgDetails = findViewById(R.id.bgDetails);
        Switch wifiSwitch = findViewById(R.id.wifiSwitch);
        bgSwitch.setChecked(cfg.getBoolean("bgCheck", false));
        wifiSwitch.setChecked(cfg.getBoolean("bgWifiOnly", true));
        bgDetails.setVisibility(cfg.getBoolean("bgCheck", false) ? View.VISIBLE : View.GONE);
        bgSwitch.setOnCheckedChangeListener((CompoundButton swB, boolean on) -> {
            cfg.edit().putBoolean("bgCheck", on).apply();
            bgDetails.setVisibility(on ? View.VISIBLE : View.GONE);
            UpdatesJobService.schedule(this);
            if (on && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                    "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
            }
            Toast.makeText(this, on ? "Фоновая проверка включена" : "Фоновая проверка выключена",
                    Toast.LENGTH_SHORT).show();
        });
        wifiSwitch.setOnCheckedChangeListener((CompoundButton swW, boolean on) -> {
            cfg.edit().putBoolean("bgWifiOnly", on).apply();
            UpdatesJobService.schedule(this);
            Toast.makeText(this, on ? "Проверяю только по Wi-Fi" : "Проверяю по любой сети",
                    Toast.LENGTH_SHORT).show();
        });
        final int[] hours = {1, 3, 6, 12, 24};
        final int[] chipIds = {R.id.chip1, R.id.chip3, R.id.chip6, R.id.chip12, R.id.chip24};
        refreshChips(chipIds, hours, cfg.getInt("bgIntervalH", 6));
        for (int i = 0; i < chipIds.length; i++) {
            final int idx = i;
            findViewById(chipIds[i]).setOnClickListener(v -> {
                cfg.edit().putInt("bgIntervalH", hours[idx]).apply();
                refreshChips(chipIds, hours, hours[idx]);
                UpdatesJobService.schedule(this);
                Toast.makeText(this, "Проверяю каждые " + hours[idx] + " ч", Toast.LENGTH_SHORT).show();
            });
        }

        findViewById(R.id.resetThreads).setOnClickListener(v -> {
            seek.setProgress(7); // 8 — оптимум
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putInt("dlThreads", 8).apply();
            Toast.makeText(this, "Вернули оптимум: 8 потоков", Toast.LENGTH_SHORT).show();
        });

        SeekBar updateSeek = findViewById(R.id.updateConcurrencySeek);
        TextView updateValue = findViewById(R.id.updateConcurrencyValue);
        int parallel = Math.max(1, Math.min(8, getSharedPreferences("cfg", MODE_PRIVATE)
                .getInt("updateConcurrency", 3)));
        updateSeek.setProgress(parallel - 1);
        updateValue.setText("Одновременно: " + parallel);
        updateSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                updateValue.setText("Одновременно: " + (progress + 1));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                int count = bar.getProgress() + 1;
                getSharedPreferences("cfg", MODE_PRIVATE).edit()
                        .putInt("updateConcurrency", count).apply();
                Toast.makeText(SettingsActivity.this,
                        "Одновременных загрузок: " + count, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void refreshChips(int[] ids, int[] hours, int sel) {
        for (int i = 0; i < ids.length; i++) {
            TextView c = findViewById(ids[i]);
            boolean on = hours[i] == sel;
            c.setTextColor(getResources().getColor(on ? R.color.accent : R.color.textSecondary));
            c.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    static String plural(int n) {
        String word;
        if (n % 10 == 1 && n != 11) word = "поток";
        else if (n % 10 >= 2 && n % 10 <= 4 && (n < 12 || n > 14)) word = "потока";
        else word = "потоков";
        return n + " " + word;
    }
}
