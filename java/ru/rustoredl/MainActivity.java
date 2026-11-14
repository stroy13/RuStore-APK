package ru.rustoredl;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.annotation.SuppressLint;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MainActivity extends Activity {

    static final String API = "https://backapi.rustore.ru/applicationData";
    static final String DONATE_URL = "https://spasibomir.ru/pay/35348";
    static final Pattern P_LINK = Pattern.compile("rustore\\.ru/catalog/app/([A-Za-z0-9._]+)");
    static final Pattern P_PKG = Pattern.compile("^[a-zA-Z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$");

    static MainActivity instance;

    Handler ui;
    EditText input;
    android.webkit.WebView webView;      // встроенный браузер RuStore
    android.view.View browserBox;
    android.widget.TextView webToggle;
    android.widget.ProgressBar webProgress;
    boolean webUp = false;               // браузер развёрнут?
    Object backCb;                       // OnBackInvokedCallback (API 33+)
    Button findBtn, dlBtn, pauseBtn, cancelBtn;
    CheckBox saveCopyCb;
    ProgressBar progress, dlProgress;
    TextView statusTv, dlStatusTv, nameTv, devTv, metaTv, pkgTv;
    OtaChecker.Info otaInfo;
    android.view.View whatsNewBox;
    TextView whatsNewTitle, whatsNewText, whatsNewMore;
    boolean whatsNewOpen;
    ImageView iconIv;
    View card, dlControls, scrim, drawer;

    SharedPreferences prefs;

    String pkg;
    String appName = "?";
    long appId = 0;
    String version = "?";

    // ---- состояние загрузки (static: переживает пересоздание Activity) ----
    static final int DL_SEGMENTS = 8;      // параллельные соединения (проверено: ~4x прирост)
    static final Object pauseLock = new Object();
    static volatile boolean dlPaused = false, dlCancelled = false, dlActive = false;
    static volatile Thread dlThread = null;   // живой поток загрузки (от «залипшего» флага)
    static volatile long dlTotal = -1;
    static final java.util.concurrent.atomic.AtomicLong dlDone =
            new java.util.concurrent.atomic.AtomicLong();
    // состояние сегментов (каждый пишет только свой поток)
    static volatile long[] segStart, segEnd, segPos;

    /** Исключение «пользователь отменил скачивание». */
    static class Cancelled extends Exception {}
    /** Резервный однопоточный режим: 206 не поддерживается / сервер рвёт соединение. */
    static class Fallback extends Exception {}

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        instance = this;
        ui = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);

        // ---- самозащита: подпись → окружение → анти-отладка
        if (!SecurityUtil.signatureOk(this)) {
            SecurityUtil.fatal(this, "Подпись приложения не совпадает — APK был модифицирован");
            return;
        }
        if (SecurityUtil.hookedEnvironment(this)) {
            SecurityUtil.fatal(this, "Обнаружены инструменты модификации (Xposed/Frida) — работа остановлена");
            return;
        }
        if (SecurityUtil.rooted) {
            Toast.makeText(this, "Устройство с root — защита работает в ограниченном режиме", Toast.LENGTH_LONG).show();
        }
        SecurityUtil.startWatchdog();

        // ---- лицензия: при первом запуске потребовать согласие
        boolean licenseNeeded = !prefs.getBoolean("licenseOk", false);
        if (licenseNeeded) ui.post(() -> showLicense(true));

        getWindow().setStatusBarColor(0xFF0B0E13);
        getWindow().setNavigationBarColor(0xFF0B0E13);
        setContentView(R.layout.activity_main);
        input = findViewById(R.id.input);
        findBtn = findViewById(R.id.find);
        dlBtn = findViewById(R.id.download);
        saveCopyCb = findViewById(R.id.saveCopy);
        progress = findViewById(R.id.progress);
        dlProgress = findViewById(R.id.dlProgress);
        dlProgress.setMax(10000); // шкала = 100.00%, чтобы полоса шла в ногу с процентами
        dlControls = findViewById(R.id.dlControls);
        pauseBtn = findViewById(R.id.pauseBtn);
        cancelBtn = findViewById(R.id.cancelBtn);
        statusTv = findViewById(R.id.status);
        dlStatusTv = findViewById(R.id.dlStatus);
        nameTv = findViewById(R.id.appname);
        devTv = findViewById(R.id.dev);
        metaTv = findViewById(R.id.meta);
        whatsNewBox = findViewById(R.id.whatsNewBox);
        whatsNewTitle = findViewById(R.id.whatsNewTitle);
        whatsNewText = findViewById(R.id.whatsNewText);
        whatsNewMore = findViewById(R.id.whatsNewMore);
        // проверка обрезки — строго после реальной разметки (post() гонится с layout)
        whatsNewText.addOnLayoutChangeListener((v, l2, t2, r2, b2, ol, ot2, or2, ob2) ->
                updateWhatsNewMore());
        whatsNewMore.setOnClickListener(v -> {
            whatsNewOpen = !whatsNewOpen;
            if (whatsNewOpen) {
                whatsNewText.setMaxLines(Integer.MAX_VALUE);
                whatsNewText.setEllipsize(null);
                whatsNewMore.setText("Свернуть");
            } else {
                whatsNewText.setMaxLines(4);
                whatsNewText.setEllipsize(android.text.TextUtils.TruncateAt.END);
                whatsNewMore.setText("Показать полностью");
            }
        });
        pkgTv = findViewById(R.id.pkg);
        iconIv = findViewById(R.id.icon);
        card = findViewById(R.id.card);
        findBtn.setOnClickListener(v -> onFind());
        dlBtn.setOnClickListener(v -> onDownload());
        pauseBtn.setOnClickListener(v -> onPauseToggle());
        cancelBtn.setOnClickListener(v -> onCancelDownload());

        // загрузка шла до пересоздания экрана — восстановим управление
        if (dlActive) {
            busy(true);
            dlUiShow(dlPaused);
            setDlStatus(dlPaused ? "Пауза" : "Скачиваю…");
        }

        saveCopyCb.setChecked(prefs.getBoolean("saveCopy", false));
        UpdatesJobService.cancelOta(this); // уведомлений нет — только диалог при запуске
        findViewById(android.R.id.content).postDelayed(() -> {
            // ВАЖНО: в checkQuiet ходит сеть — только из фонового потока,
            // иначе NetworkOnMainThreadException и тихий null (диалог не покажется)
            new Thread(() -> {
                final OtaChecker.Info i = OtaChecker.checkQuiet(getApplicationContext());
                if (i == null) return;
                ui.post(() -> { if (!isFinishing() && !isDestroyed()) showOtaDialog(i); });
            }, "ota-check").start();
        }, 3000);
        saveCopyCb.setOnCheckedChangeListener((btn, on) ->
                prefs.edit().putBoolean("saveCopy", on).apply());
        // ---- боковое меню
        scrim = findViewById(R.id.scrim);
        drawer = findViewById(R.id.drawer);
        findViewById(R.id.menuBtn).setOnClickListener(v -> openDrawer());
        scrim.setOnClickListener(v -> closeDrawer());
        findViewById(R.id.mHome).setOnClickListener(v -> closeDrawer());
        findViewById(R.id.mHistory).setOnClickListener(v -> {
            closeDrawer();
            startActivity(new Intent(this, HistoryActivity.class));
        });
        findViewById(R.id.mSettings).setOnClickListener(v -> {
            closeDrawer();
            startActivity(new Intent(this, SettingsActivity.class));
        });
        findViewById(R.id.mUpdates).setOnClickListener(v -> {
            closeDrawer();
            startActivity(new Intent(this, UpdatesActivity.class));
        });
        findViewById(R.id.mAbout).setOnClickListener(v -> { closeDrawer(); showAbout(); });
        findViewById(R.id.mLicense).setOnClickListener(v -> { closeDrawer(); showLicense(false); });
        findViewById(R.id.mDonate).setOnClickListener(v -> { closeDrawer(); openDonate(); });
        ((TextView) findViewById(R.id.drawerVersion)).setText("Версия " + appVersion());
        drawer.post(() -> drawer.setTranslationX(-(drawer.getWidth() + 40)));

        // подвал «Сделано Trykath», прибит к низу → окно «О программе»
        TextView made = findViewById(R.id.madeBy);
        String pre = "Сделано ", name = "Trykath";
        SpannableStringBuilder sb = new SpannableStringBuilder(pre + name);
        int ns = pre.length();
        sb.setSpan(new StyleSpan(Typeface.BOLD), ns, sb.length(), 0);
        sb.setSpan(new ForegroundColorSpan(0xFF6E9BF0), ns, sb.length(), 0);
        made.setText(sb);
        made.setOnClickListener(v -> showAbout());

        // блок поддержки на главной
        View donateCard = findViewById(R.id.donateCard);
        View donateBtn = findViewById(R.id.donateBtn);
        View.OnClickListener donateClick = v -> openDonate();
        if (donateCard != null) donateCard.setOnClickListener(donateClick);
        if (donateBtn != null) donateBtn.setOnClickListener(donateClick);

        setupWebBrowser();

        // Android 13+/predictive back: onBackPressed не вызывается —
        // обрабатываем «назад» через системный dispatcher
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            backCb = new android.window.OnBackInvokedCallback() {
                @Override public void onBackInvoked() { handleBack(); }
            };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    (android.window.OnBackInvokedCallback) backCb);
        }

        // автообновление приложений (если включено в Настройках)
        AutoUpdater.maybeRun(this);
    }

    float density() { return getResources().getDisplayMetrics().density; }

    void openDrawer() {
        drawer.setVisibility(View.VISIBLE);
        drawer.animate().translationX(0).setDuration(200).start();
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(200).start();
    }

    void closeDrawer() {
        drawer.animate().translationX(-(drawer.getWidth() + 40)).setDuration(180)
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
        scrim.animate().alpha(0f).setDuration(180)
                .withEndAction(() -> scrim.setVisibility(View.GONE)).start();
    }

    boolean drawerOpen() {
        return drawer.getVisibility() == View.VISIBLE
                && drawer.getTranslationX() > -(drawer.getWidth() / 2f);
    }

    static void status(final Activity a, final String s) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        a.runOnUiThread(() -> {
            try {
                TextView st = a.findViewById(R.id.status);
                if (st != null) st.setText(s);
            } catch (Exception ignore) {}
        });
    }

    static String appVersionStatic() {
        MainActivity m = instance;
        if (m != null) return m.appVersion();
        try {
            return getStaticContext().getPackageManager()
                    .getPackageInfo(getStaticContext().getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    private static android.content.Context getStaticContext() {
        MainActivity m = instance;
        if (m != null) return m;
        throw new IllegalStateException("app not running");
    }

    String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    void handleBack() {
        if (drawerOpen()) { closeDrawer(); return; }
        if (webUp) {
            if (webView.canGoBack()) { webView.goBack(); return; }
            toggleWeb(); // свернуть браузер, а не выходить
            return;
        }
        showExitDialog();
    }

    void showExitDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_exit, null);
        AlertDialog d = new AlertDialog.Builder(this).setView(v).create();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        v.findViewById(R.id.exitStay).setOnClickListener(x -> d.dismiss());
        v.findViewById(R.id.exitLeave).setOnClickListener(x -> { d.dismiss(); finish(); });
        d.show();
    }

    @Override
    public void onBackPressed() {
        handleBack(); // до API 32 (на 33+ работает OnBackInvokedCallback)
    }

    /** Лицензия: first=true — принять/отклонить (первый запуск); false — просмотр. */
    static final String PDA_URL = "https://4pda.to/forum/index.php?showuser=5594067";

    /** 4PDA: профиль внутри приложения → домашний экран приложения → сайт. */
    void open4pda() {
        Uri url = Uri.parse(PDA_URL);
        String pkg = "ru.fourpda.client";

        // 1) открыть ссылку прямо в приложении 4PDA (если оно обрабатывает ссылки 4pda.to)
        try {
            Intent inApp = new Intent(Intent.ACTION_VIEW, url);
            inApp.setPackage(pkg);
            if (getPackageManager().resolveActivity(inApp, 0) != null) {
                startActivity(inApp);
                return;
            }
        } catch (Exception ignore) {}

        // 2) просто запустить приложение 4PDA
        try {
            Intent home = getPackageManager().getLaunchIntentForPackage(pkg);
            if (home != null) { startActivity(home); return; }
        } catch (Exception ignore) {}

        // 3) другой обработчик 4pda.to (не браузер)? откроем через него
        try {
            java.util.List<android.content.pm.ResolveInfo> acts =
                    getPackageManager().queryIntentActivities(
                            new Intent(Intent.ACTION_VIEW, url), 0);
            String self = getPackageName();
            for (android.content.pm.ResolveInfo ri : acts) {
                String q = ri.activityInfo.packageName.toLowerCase();
                if (!q.equals(self) && !q.contains("browser") && !q.contains("chrome")
                        && !q.contains("firefox") && !q.contains("opera")
                        && !q.contains("yandex") && !q.contains("edge")
                        && !q.contains("internet") && !q.contains("globalbrowser")) {
                    Intent i = new Intent(Intent.ACTION_VIEW, url);
                    i.setPackage(ri.activityInfo.packageName);
                    startActivity(i);
                    return;
                }
            }
        } catch (Exception ignore) {}

        // 4) остался браузер
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, url));
        } catch (Exception e) {
            Toast.makeText(this, "Нет браузера для открытия ссылки", Toast.LENGTH_SHORT).show();
        }
    }

    void openDonate() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Нет браузера для открытия ссылки", Toast.LENGTH_SHORT).show();
        }
    }

    void showLicense(final boolean first) {
        View v = getLayoutInflater().inflate(R.layout.dialog_license, null);
        ((TextView) v.findViewById(R.id.licenseText)).setText(R.string.license_text);
        AlertDialog d = new AlertDialog.Builder(this).setView(v).setCancelable(!first).create();
        if (d.getWindow() != null)
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        v.findViewById(R.id.btnDecline).setVisibility(first ? View.VISIBLE : View.GONE);
        Button ok = v.findViewById(R.id.btnAccept);
        ok.setText(first ? "Принимаю" : "Закрыть");
        ok.setOnClickListener(x -> {
            if (first) prefs.edit().putBoolean("licenseOk", true).apply();
            d.dismiss();
        });
        if (first) {
            v.findViewById(R.id.btnDecline).setOnClickListener(x -> {
                Toast.makeText(this, "Без принятия лицензии приложение не работает",
                        Toast.LENGTH_LONG).show();
                finishAffinity();
            });
        }
        d.show();
    }

    void showAbout() {
        View v = getLayoutInflater().inflate(R.layout.dialog_about, null);
        ((TextView) v.findViewById(R.id.aboutVersion)).setText("Версия " + appVersion());
        v.findViewById(R.id.pdaRow).setOnClickListener(x -> open4pda());
        AlertDialog d = new AlertDialog.Builder(this).setView(v).create();
        if (d.getWindow() != null)
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        d.show();
    }

    @Override
    protected void onDestroy() {
        if (backCb != null && android.os.Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) backCb);
        if (webView != null) webView.destroy();
        super.onDestroy();
        if (instance == this) instance = null;
    }

    public void busy(boolean b) {
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        findBtn.setEnabled(!b);
        dlBtn.setEnabled(!b);
    }

    public void status(String s) {
        statusTv.setText(s);
        statusTv.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------- поиск карточки

    // ================================================== встроенный браузер

    @SuppressLint("SetJavaScriptEnabled")
    void setupWebBrowser() {
        browserBox = findViewById(R.id.browserBox);
        webToggle = findViewById(R.id.webToggle);
        webProgress = findViewById(R.id.webProgress);
        // WebView создаётся ЛЕНИВО (createWeb при первом развороте):
        // первый запуск Chromium-движка тяжёлый, на холодном старте под
        // нагрузкой он может подвесить UI-поток и дать ANR
        webToggle.setOnClickListener(v -> toggleWeb());
    }

    /** Создание WebView при первом развороте браузера. */
    void createWeb() {
        if (webView != null) return;
        // Сайт принудительно в СВЕТЛОЙ теме: WebView с конфигурацией «не ночь» —
        // иначе сайт включает свою тёмную тему (тёмный текст на тёмном фоне).
        android.content.res.Configuration wc = new android.content.res.Configuration(
                getResources().getConfiguration());
        wc.uiMode = (wc.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                | android.content.res.Configuration.UI_MODE_NIGHT_NO;
        webView = new android.webkit.WebView(createConfigurationContext(wc));
        ((android.widget.LinearLayout) browserBox).addView(webView,
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setBackgroundColor(0xFFFFFFFF);
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(android.webkit.WebView w, String url) {
                if (url == null) return true;
                // мобильный сайт любит фирменную схему rustore://… — превращаем в https
                if (url.startsWith("rustore://")) {
                    // страницу НЕ перезагружаем: сайт сам зовёт rustore://,
                    // конвертация обратно привела бы к циклу перезагрузок
                    // (и постоянным «прыжкам» вёрстки)
                    String https = rustoreToHttps(url);
                    if (https != null) {
                        java.util.regex.Matcher mp = java.util.regex.Pattern
                                .compile("/catalog/app/([A-Za-z0-9._]+)")
                                .matcher(https);
                        if (mp.find()) {
                            webProgress.setVisibility(View.GONE);
                            onAppFoundInWeb(mp.group(1)); // карточка сразу
                        }
                    }
                    return true;
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try { // intent://, market:// и прочее — наружу
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception ignore) {}
                    return true;
                }
                java.net.URI u;
                try { u = new java.net.URI(url); } catch (Exception e) { return true; }
                String host = u.getHost() == null ? "" : u.getHost();
                if (!host.endsWith("rustore.ru")) {
                    try { // чужой домен — только наружу, в браузер системы
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception ignore) {}
                    return true;
                }
                // страница приложения? /catalog/app/<package>
                String path = u.getPath() == null ? "" : u.getPath();
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("/catalog/app/([A-Za-z0-9._]+)")
                        .matcher(path);
                if (m.find()) onAppFoundInWeb(m.group(1));
                return false; // грузим в нашем WebView (пины *.rustore.ru действуют)
            }

            @Override
            public void onReceivedError(android.webkit.WebView w,
                                        android.webkit.WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                if (req.isForMainFrame()) showErrorPage(w, String.valueOf(err.getDescription()));
            }

            @Override
            public void onPageFinished(android.webkit.WebView w, String url) {
                webProgress.setVisibility(View.GONE);
            }
        });
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(android.webkit.WebView w, int newProgress) {
                if (newProgress < 100) {
                    webProgress.setVisibility(View.VISIBLE);
                    webProgress.setProgress(newProgress);
                } else {
                    webProgress.setVisibility(View.GONE);
                }
            }
        });
    }

    /** rustore://apps.rustore.ru/app/pkg → https://www.rustore.ru/catalog/app/pkg. */
    static String rustoreToHttps(String url) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("rustore://[^/]+/app/([A-Za-z0-9._]+)")
                .matcher(url);
        if (m.find()) return "https://www.rustore.ru/catalog/app/" + m.group(1);
        int i = url.indexOf("://");
        return i > 0 ? "https://" + url.substring(i + 3) : null;
    }

    /** Тёмная страница ошибки в стиле приложения (вместо чёрной дефолтной). */
    void showErrorPage(android.webkit.WebView w, String reason) {
        webProgress.setVisibility(View.GONE);
        String html = "<html><body style=\"background:#0B0E13;color:#E7EAF2;"
                + "font-family:sans-serif;padding:28px;text-align:center;\">"
                + "<p style=\"font-size:17px;font-weight:bold;margin-top:36px;\">"
                + "Не удалось загрузить страницу</p>"
                + "<p style=\"color:#8B93A7;font-size:13px;\">" + reason + "</p>"
                + "<a href=\"javascript:location.reload()\" style=\"display:inline-block;"
                + "margin-top:22px;background:#5B7CE0;color:#fff;text-decoration:none;"
                + "padding:11px 26px;border-radius:12px;font-size:14px;\">Повторить</a>"
                + "</body></html>";
        w.loadData(html, "text/html; charset=utf-8", "utf-8");
    }

    /** Разворот/сворачивание браузера. При первом развороте — каталог. */
    void toggleWeb() {
        webUp = !webUp;
        if (webUp) createWeb(); // ленивая инициализация браузера
        browserBox.setVisibility(webUp ? View.VISIBLE : View.GONE);
        webToggle.setText(webUp ? "Свернуть браузер  ▴" : "Браузер RuStore  ▾");
        if (webUp && webView.getUrl() == null) {
            webView.loadUrl("https://www.rustore.ru/catalog");
        }
    }

    /** Открыли страницу приложения в браузере — сами подставим пакет и найдём. */
    void onAppFoundInWeb(final String pkg) {
        runOnUiThread(() -> {
            if (pkg.equals(input.getText().toString().trim())) return;
            input.setText(pkg);
            input.clearFocus(); // без этого выскакивает клавиатура и дёргает экран
            onFind();
            // карточка сверху — покажем её
            findViewById(R.id.content).post(() -> ((ScrollView) findViewById(R.id.content)).smoothScrollTo(0, 0));
        });
    }

    void onFind() {
        final String q = input.getText().toString().trim();
        if (q.isEmpty()) { status("Вставь ссылку на приложение из RuStore"); return; }
        final String p = parsePkg(q);
        if (p == null) { status("Не похоже на ссылку RuStore или имя пакета"); return; }
        busy(true);
        card.setVisibility(View.GONE);
        status("Ищу приложение…");
        new Thread(() -> {
            try {
                JSONObject body = api("GET", API + "/overallInfo/" + p, null).getJSONObject("body");
                pkg = body.getString("packageName");
                appId = body.getLong("appId");
                version = body.optString("versionName", "?");
                appName = body.optString("appName", pkg);
                final String dev = body.optString("companyName", "");
                final long size = body.optLong("fileSize", 0);
                final int minSdk = body.optInt("minSdkVersion", 0);
                final int minAndroid = body.optInt("minAndroidVersion", 0); // готовая версия «9», «6»…
                JSONObject rt = body.optJSONObject("rating");
                final double rating = rt == null ? 0 : rt.optDouble("average", 0);
                final String whatsNew = body.optString("whatsNew", "").trim();
                Bitmap icon = null;
                try {
                    InputStream is = openStream(body.getString("iconUrl"), 15000);
                    icon = BitmapFactory.decodeStream(is);
                    is.close();
                } catch (Exception ignore) {}
                final Bitmap fIcon = icon;
                final String fPkg = pkg;
                ui.post(() -> {
                    nameTv.setText(appName);
                    devTv.setText(dev);
                    String meta = "Версия: " + version + "   •   " + human(size);
                    if (minAndroid > 0)
                        meta += "   •   Android: " + minAndroid + "+";
                    else if (minSdk > 0)
                        meta += "   •   Android: " + UpdatesActivity.minSdkVersionName(minSdk) + "+";
                    if (rating > 0) meta += "   •   ★ " + rating;
                    metaTv.setText(meta);
                    setupWhatsNew(whatsNew, version);
                    pkgTv.setText(fPkg);
                    if (fIcon != null) iconIv.setImageBitmap(fIcon);
                    card.setVisibility(View.VISIBLE);
                    status("Готово к скачиванию");
                    busy(false);
                });
            } catch (Exception e) {
                ui.post(() -> { busy(false); status("Ошибка: " + e.getMessage()); });
            }
        }).start();
    }

    /** «Что нового» → аккуратный список: каждый пункт с новой строки, «• текст».
     *  Нумерацию («1.», «2)») и чужие маркеры заменяем своим буллетом. */
    static String formatWhatsNew(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : raw.replace("\r", "").split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            t = t.replaceFirst("^(\\d+[.)\\]]|[-–—*•])\\s+", "");
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append("• ").append(t);
        }
        return sb.toString();
    }

    /** Блок «Что нового»: свёрнут до 4 строк, кнопка видна только если текст длиннее. */
    void setupWhatsNew(String text, String ver) {
        text = formatWhatsNew(text);
        if (text.isEmpty()) {
            whatsNewBox.setVisibility(android.view.View.GONE);
            return;
        }
        whatsNewBox.setVisibility(android.view.View.VISIBLE);
        whatsNewTitle.setText("Что нового в версии " + ver);
        whatsNewText.setText(text);
        whatsNewOpen = false;
        whatsNewText.setMaxLines(4);
        whatsNewText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        whatsNewMore.setText("Показать полностью");
        whatsNewMore.setVisibility(android.view.View.GONE);
        updateWhatsNewMore(); // точный вердикт придёт от слушателя раскладки
    }

    /** «Показать полностью» — только если в свёрнутом виде текст обрезан многоточием.
     *  Вызывается из слушателя раскладки: до реальной разметки Layout ещё null. */
    void updateWhatsNewMore() {
        if (whatsNewOpen) return; // развёрнутый текст: кнопка уже «Свернуть»
        android.text.Layout l = whatsNewText.getLayout();
        // при maxLines=4 getLineCount() возвращает обрезанное число (<=4),
        // поэтому спрашиваем у раскладки, поставила ли она многоточие
        boolean truncated = l != null
                && l.getEllipsisCount(l.getLineCount() - 1) > 0;
        whatsNewMore.setVisibility(truncated
                ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    // ---------------------------------------------------------- скачивание + установка

    /** Интерфейс прогресса загрузки для любого экрана. */
    interface DlUi {
        void status(String s);
        void progress(boolean indeterminate, int percent, long done, long total);
        void finished(String msg, boolean ok);
        default int threads() { return 8; }
    }

    /** Полный цикл: ссылка → скачивание → (копия) → история → установка. */
    static void performDownload(Context ctx, String pkg, String appName, long appId,
                                String version, boolean saveCopy, DlUi uiRaw) {
        dlPaused = false; dlCancelled = false; dlDone.set(0); dlTotal = -1;
        // защита от параллельных скачиваний (общий .part-файл).
        // «Залипший» флаг (поток умер, не успев прибраться) не блокирует:
        // загрузкой считаем только живой поток
        if (dlActive && dlThread != null && dlThread.isAlive()) {
            uiRaw.finished("Уже идёт другая загрузка — подожди её окончания", false);
            return;
        }
        // все колбэки — строго в UI-поток (поток скачивания не трогает view)
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final DlUi ui = new DlUi() {
            @Override public void status(String s) { main.post(() -> uiRaw.status(s)); }
            @Override public void progress(boolean i, int p, long d, long t) {
                main.post(() -> uiRaw.progress(i, p, d, t));
            }
            @Override public void finished(String m, boolean ok) { main.post(() -> uiRaw.finished(m, ok)); }
            @Override public int threads() { return uiRaw.threads(); }
        };
        Thread t = new Thread(() -> {
            dlThread = Thread.currentThread();
            dlActive = true;
            File part = new File(ctx.getCacheDir(), "rustore_dl.zip.part");
            try {
                JSONObject r = RuStoreApi.request(ctx, "POST", API + "/download-link",
                        new JSONObject().put("appId", appId).put("firstInstall", true),
                        s -> ui.status(s));
                String apkUrl = r.getJSONObject("body").getString("apkUrl");
                ui.status("Скачиваю…");
                downloadControlled(apkUrl, part, ui);

                File zip = new File(ctx.getCacheDir(), "rustore_dl.zip");
                part.renameTo(zip);
                dlActive = false;
                ui.status("Распаковываю…");
                List<File> apks = extractApks(ctx, zip);
                zip.delete();
                if (apks.isEmpty()) throw new Exception("в архиве нет APK");

                JSONArray saved = new JSONArray();
                if (saveCopy) {
                    ui.status("Сохраняю копию в Загрузки…");
                    saved = saveToDownloads(ctx, pkg, version, apks);
                }
                HistoryActivity.addRecord(ctx, pkg, appName, version, apks, saved);
                ui.status("Устанавливаю (" + apks.size() + (apks.size() == 1 ? " файл)…" : " файлов)…"));
                install(ctx, apks, pkg, version);
                ui.finished("Запрошена установка", true);
            } catch (Cancelled c) {
                dlActive = false;
                part.delete();
                ui.finished("Скачивание отменено", false);
            } catch (Exception e) {
                dlActive = false;
                ui.finished("Ошибка: " + e.getMessage(), false);
            }
        });
        t.start();
    }

    void onDownload() {
        if (pkg == null || appId == 0) { status("Сначала найди приложение"); return; }
        if (!ensureInstallPermission(this)) return;
        final boolean saveCopy = saveCopyCb.isChecked();
        if (saveCopy && Build.VERSION.SDK_INT <= 28 && checkSelfPermission(
                "android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
            status("Дай разрешение на запись — сохраню копию в Загрузки, затем нажми «Скачать» ещё раз");
            requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 1);
            return;
        }
        // Android 13+: попросим разрешение на уведомления — виден прогресс в шторке
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);

        // «Уже скачано»: эта версия лежит в истории с живыми файлами — предложим установить
        JSONObject already = HistoryActivity.findRecord(this, pkg, version);
        if (already != null) { offerAlready(already, saveCopy); return; }
        startDownload(saveCopy);
    }

    void startDownload(boolean saveCopy) {
        busy(true);
        dlProgress.setProgress(0);
        dlUiShow(false);
        setDlStatus("Получаю ссылку…");
        final String fPkg = pkg, fVer = version, fName = appName;
        final long fAppId = appId;
        DownloadService.start(this, fPkg, fName, fAppId, fVer, saveCopy, new DlUi() {
            @Override public void status(String s) { setDlStatus(s); }
            @Override public void progress(boolean indet, int pct, long done, long total) {
                dlProgress.setVisibility(View.VISIBLE);
                dlProgress.setIndeterminate(indet);
                if (!indet) dlProgress.setProgress(pct * 100);
                setDlStatus(indet
                        ? "Скачиваю… " + human(done)
                        : "Скачиваю… " + pct + "%  (" + human(done) + " из " + human(total) + ")");
            }
            @Override public void finished(String msg, boolean ok) {
                dlUiHide(); busy(false); status(msg);
            }
            @Override public int threads() { return prefs.getInt("dlThreads", DL_SEGMENTS); }
        });
    }

    /** Красивый диалог «Доступно обновление» — в фирменном стиле bg_card. */
    void showOtaDialog(final OtaChecker.Info i) {
        otaInfo = i;
        View v = getLayoutInflater().inflate(R.layout.dialog_ota, null);
        TextView ver = v.findViewById(R.id.otaVersion);
        View notesBox = v.findViewById(R.id.otaNotesBox);
        TextView notesTv = v.findViewById(R.id.otaNotes);
        String verText = "Версия " + i.version + (i.size > 0 ? "  ·  " + human(i.size) : "");
        ver.setText(verText);
        if (i.notes != null && !i.notes.trim().isEmpty()) {
            String b = bulletize(i.notes);
            // в макете уже есть заголовок «Что нового:» — убираем дубль из notes
            if (b.startsWith("Что нового:")) {
                b = b.substring("Что нового:".length()).trim();
            } else if (b.startsWith("Что нового")) {
                int nl = b.indexOf('\n');
                if (nl > 0 && b.substring(0, nl).trim().equalsIgnoreCase("Что нового")) {
                    b = b.substring(nl + 1).trim();
                }
            }
            if (b.isEmpty()) {
                notesBox.setVisibility(View.GONE);
            } else {
                notesBox.setVisibility(View.VISIBLE);
                notesTv.setText(b);
            }
        } else {
            notesBox.setVisibility(View.GONE);
        }
        AlertDialog d = new AlertDialog.Builder(this).setView(v).create();
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        v.findViewById(R.id.otaLater).setOnClickListener(x -> d.dismiss());
        v.findViewById(R.id.otaUpdate).setOnClickListener(x -> { d.dismiss(); startOtaDownload(); });
        d.show();
    }

    /** Каждый пункт с новой строки; буллет ставим сами, если его нет. Заголовок «Что нового:» — без буллета. */
    static String bulletize(String s) {
        StringBuilder b = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (b.length() > 0) b.append('\n');
            if (t.equalsIgnoreCase("Что нового:") || t.equalsIgnoreCase("Что нового")) {
                b.append(t);
            } else {
                b.append(t.startsWith("•") || t.startsWith("-") || t.startsWith("*") ? t : "• " + t);
            }
        }
        return b.toString();
    }

    /** Скачивание собственной свежей версии с хостинга + установка.
     *  Прогресс — внутри диалога; «Отменить» прерывает загрузку. */
    void startOtaDownload() {
        final OtaChecker.Info inf = otaInfo;
        if (inf == null) return;
        if (DownloadService.busy()) { status("Сначала дождись текущую загрузку"); return; }
        if (!ensureInstallPermission(this)) return;

        View pv = getLayoutInflater().inflate(R.layout.dialog_ota_progress, null);
        final TextView verTv = pv.findViewById(R.id.otaProgressVersion);
        final TextView pct = pv.findViewById(R.id.otaPct);
        final TextView details = pv.findViewById(R.id.otaDetails);
        final android.widget.ProgressBar bar = pv.findViewById(R.id.otaBar);
        verTv.setText("Версия " + inf.version + (inf.size > 0 ? "  ·  " + human(inf.size) : ""));
        pct.setText("0%");
        details.setText("Подготовка…");
        bar.setProgress(0);
        final AlertDialog dlg = new AlertDialog.Builder(this).setView(pv).create();
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        pv.findViewById(R.id.otaCancel).setOnClickListener(x -> { onCancelDownload(); dlg.dismiss(); });
        dlg.setCanceledOnTouchOutside(false);
        dlg.show();

        android.util.Log.d("RuStoreOTA", "startOtaDownload ver=" + inf.version + " vc=" + inf.verCode + " url=" + inf.url);
        status("Скачиваю обновление " + inf.version + "…");
        final File apk = new File(getCacheDir(), "ota_update.apk");
        new Thread(() -> {
            dlThread = Thread.currentThread(); // пауза/отмена работают и тут
            dlActive = true;
            try {
                android.util.Log.d("RuStoreOTA", "ota download start -> " + apk.getAbsolutePath());
                downloadControlled(inf.url, apk, new DlUi() {
                    @Override public void status(String st) { }
                    @Override public void progress(boolean indet, int p, long done, long total) {
                        ui.post(() -> {
                            bar.setIndeterminate(indet);
                            if (indet) {
                                bar.setProgress(0);
                                pct.setText("…");
                                details.setText(human(done));
                            } else {
                                bar.setProgress(p * 100);
                                pct.setText(p + "%");
                                details.setText(human(done) + " из " + human(total));
                            }
                        });
                    }
                    @Override public void finished(String m, boolean ok) {}
                    @Override public int threads() { return prefs.getInt("dlThreads", DL_SEGMENTS); }
                });
                ui.post(() -> { pct.setText("✓"); details.setText("Проверяю целостность…"); bar.setIndeterminate(true); });
                if (inf.sha256 != null && !inf.sha256.isEmpty()) {
                    String real = OtaChecker.sha256(apk);
                    android.util.Log.d("RuStoreOTA", "sha expect=" + inf.sha256 + " real=" + real);
                    if (!real.equalsIgnoreCase(inf.sha256)) {
                        apk.delete();
                        throw new Exception("файл повреждён — попробуй обновиться позже");
                    }
                }
                android.util.Log.d("RuStoreOTA", "ota apk downloaded " + apk.length() + " bytes, calling install");
                if (!getPackageManager().canRequestPackageInstalls()) {
                    throw new Exception("нет разрешения «Установка неизвестных приложений» — выдай в системе и нажми Обновить снова");
                }
                install(this, java.util.Collections.singletonList(apk));
                dlActive = false;
                android.util.Log.d("RuStoreOTA", "install() committed, waiting for system dialog");
                ui.post(() -> {
                    dlg.dismiss();
                    status("Запрошено обновление до " + inf.version + " — подтверди на экране. Если окна нет — смотри уведомление и Настройки → Всплывающие окна → Разрешить для RuStoreDL");
                });
            } catch (Cancelled c) {
                android.util.Log.d("RuStoreOTA", "ota cancelled");
                dlActive = false;
                apk.delete();
                ui.post(() -> { dlg.dismiss(); status("Обновление отменено"); });
            } catch (Exception e) {
                android.util.Log.e("RuStoreOTA", "ota failed", e);
                dlActive = false;
                apk.delete();
                ui.post(() -> { dlg.dismiss(); status("Ошибка обновления: " + e.getMessage()); });
            }
        }, "ota-dl").start();
    }

    /** Эта версия уже в истории — установить сохранённое или скачать заново? */
    void offerAlready(JSONObject rec, boolean saveCopy) {
        final List<File> fs = HistoryActivity.recordFiles(this, rec);
        new AlertDialog.Builder(this)
                .setTitle(appName)
                .setMessage("Версия " + version + " уже скачана ("
                        + human(HistoryActivity.filesSize(fs))
                        + " в кэше). Установить сохранённый файл или скачать заново?")
                .setPositiveButton("Установить", (d, w) -> {
                    busy(true);
                    status("Устанавливаю сохранённый файл…");
                    HistoryActivity.installFiles(this, fs, rec);
                })
                .setNegativeButton("Скачать заново", (d, w) -> startDownload(saveCopy))
                .setNeutralButton("Отмена", null)
                .show();
    }

    // ---------------------------------------------------------- пауза / отмена загрузки

    void onPauseToggle() {
        if (!dlActive) return;
        boolean paused = togglePauseDownload();
        pauseBtn.setText(paused ? "▶  Продолжить" : "❚❚  Пауза");
        setDlStatus(paused ? "Пауза" : "Скачиваю…");
    }

    /** Синхронизация кнопки и статуса, когда паузу переключили из уведомления. */
    void syncPauseUi() {
        if (!dlActive) return;
        pauseBtn.setText(dlPaused ? "▶  Продолжить" : "❚❚  Пауза");
        setDlStatus(dlPaused ? "Пауза" : "Скачиваю…");
    }

    /** Пауза/продолжение из любого места (экран, уведомление). Возвращает новое состояние. */
    static boolean togglePauseDownload() {
        if (!dlActive) return dlPaused;
        dlPaused = !dlPaused;
        if (!dlPaused) synchronized (pauseLock) { pauseLock.notifyAll(); }
        return dlPaused;
    }

    static void onCancelDownload() {
        if (!dlActive) return;
        dlCancelled = true;
        dlPaused = false;
        synchronized (pauseLock) { pauseLock.notifyAll(); }
    }

    /** Панель управления загрузкой вместо кнопки «Скачать». */
    void dlUiShow(boolean paused) {
        statusTv.setVisibility(View.GONE);
        dlStatusTv.setTextColor(getResources().getColor(R.color.textSecondary));
        dlStatusTv.setVisibility(View.VISIBLE);
        dlControls.setVisibility(View.VISIBLE);
        dlBtn.setVisibility(View.GONE);
        dlProgress.setVisibility(View.VISIBLE);
        pauseBtn.setText(paused ? "▶  Продолжить" : "❚❚  Пауза");
        findBtn.setEnabled(false);
    }

    void dlUiHide() {
        dlStatusTv.setVisibility(View.GONE);
        dlControls.setVisibility(View.GONE);
        dlProgress.setVisibility(View.GONE);
        dlBtn.setVisibility(View.VISIBLE);
        if (!dlActive) findBtn.setEnabled(true);
    }

    /** Блокируется, пока стоит пауза; бросает Cancelled при отмене. */
    static void awaitResume() throws Cancelled {
        long deadline = System.currentTimeMillis() + 10L * 60 * 1000; // пауза ≠ навсегда
        while (dlPaused && !dlCancelled) {
            synchronized (pauseLock) {
                try { pauseLock.wait(1000); } catch (InterruptedException ignore) {}
            }
            if (dlPaused && !dlCancelled && System.currentTimeMillis() > deadline)
                throw new Cancelled(); // 10 минут на паузе — завершаем
        }
        if (dlCancelled) throw new Cancelled();
    }

    static void updateDlProgress(DlUi ui) {
        final long d = dlDone.get(), t = dlTotal;
        if (t > 0) {
            int pct = (int) (d * 100 / t);
            ui.progress(false, pct, d, t);
        } else {
            ui.progress(true, 0, d, -1);
        }
    }

    static long probeTotal(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            SecurityUtil.applyTo(c);
            c.setConnectTimeout(20000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            long total = -1;
            if (code == 206) {
                String cr = c.getHeaderField("Content-Range");
                if (cr != null && cr.contains("/"))
                    try { total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1)); } catch (Exception ignore) {}
            }
            c.disconnect();
            return total;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Скачивание с паузой/отменой. Если сервер поддерживает Range (CDN RuStore
     * отвечает 206) — файл делится на DL_SEGMENTS частей, части качаются
     * параллельно (до ~4–5x быстрее). Иначе — одним соединением.
     */
    static void downloadControlled(String url, File part, DlUi ui) throws Exception {
        long total = probeTotal(url);
        dlTotal = total;
        part.delete();
        if (total > 1_000_000) {
            try {
                downloadSegmented(url, part, total, ui);
                return;
            } catch (Fallback f) {
                // сервер перестал отдавать 206 — начнём заново одним потоком
            }
        }
        dlTotal = -1;
        dlDone.set(0);
        part.delete(); // после сегментов в файле могут быть «дыры» — начинаем честно
        downloadSingle(url, part, ui);
    }

    static void downloadSegmented(String url, File part, long total, DlUi ui) throws Exception {
        int n = Math.max(1, Math.min(16, ui.threads()));
        long chunk = (total + n - 1) / n;
        segStart = new long[n];
        segEnd = new long[n];
        segPos = new long[n];
        for (int i = 0; i < n; i++) {
            segStart[i] = (long) i * chunk;
            segEnd[i] = Math.min(total, (long) (i + 1) * chunk) - 1;
            segPos[i] = segStart[i];
        }
        final Object[] err = new Object[1];
        for (int attempt = 1; ; attempt++) {
            Thread[] th = new Thread[n];
            for (int i = 0; i < n; i++) {
                final int k = i;
                th[k] = new Thread(() -> runSegment(url, part, k, err, ui), "dl-" + k);
                th[k].start();
            }
            for (Thread t : th) t.join();
            if (err[0] instanceof Cancelled) throw (Cancelled) err[0];
            if (err[0] instanceof Fallback) throw (Fallback) err[0];
            if (err[0] instanceof Exception) {
                if (attempt >= 4) throw (Exception) err[0];
                // обрыв сети: куски уже на диске — продолжаем с тех же позиций
                ui.status("Сеть пропала, докачиваю с места разрыва…");
                try { Thread.sleep(1500L * attempt); } catch (InterruptedException ie) {}
                err[0] = null;
                continue;
            }
            break;
        }
        updateDlProgress(ui);
    }

    /** Тело одного сегмента: докачка со своего места, ретраи, пауза, отмена. */
    private static void runSegment(String url, File part, int k, Object[] err, DlUi ui) {
        byte[] buf = new byte[65536];
        long lastUi = 0;
        int fails = 0;
        try {
            while (true) {
                if (dlCancelled) throw new Cancelled();
                if (err[0] != null) return;
                if (dlPaused) { synchronized (pauseLock) { pauseLock.wait(); } continue; }
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                SecurityUtil.applyTo(c);
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setRequestProperty("Range", "bytes=" + segPos[k] + "-" + segEnd[k]);
                int code;
                InputStream in;
                try {
                    code = c.getResponseCode();
                    if (code != 206) { c.disconnect(); throw new Fallback(); }
                    in = c.getInputStream();
                } catch (Fallback e) {
                    throw e;
                } catch (Exception e) {
                    c.disconnect();
                    if (++fails > 10) { synchronized (err) { if (err[0] == null) err[0] = e; } return; }
                    Thread.sleep(400L * fails);
                    continue;
                }
                RandomAccessFile raf = new RandomAccessFile(part, "rw");
                try {
                    raf.seek(segPos[k]);
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        if (dlCancelled) throw new Cancelled();
                        if (dlPaused) break;                       // пауза: выйдем и закроем соединение
                        if (err[0] != null) return;
                        raf.write(buf, 0, r);
                        segPos[k] += r;
                        dlDone.addAndGet(r);
                        long now = System.currentTimeMillis();
                        if (now - lastUi > 300) { lastUi = now; updateDlProgress(ui); }
                    }
                } finally {
                    raf.close();
                    in.close();
                    c.disconnect();
                }
                if (segPos[k] > segEnd[k]) return;                 // сегмент докачан
                fails = 0;                                          // соединение оборвалось — переподключимся
            }
        } catch (Cancelled e) {
            synchronized (err) { if (err[0] == null) err[0] = e; }
        } catch (Fallback e) {
            synchronized (err) { if (err[0] == null) err[0] = e; }
        } catch (Exception e) {
            synchronized (err) { if (err[0] == null) err[0] = e; }
        }
    }

    static boolean ensureInstallPermission(Activity a) {
        if (a.getPackageManager().canRequestPackageInstalls()) return true;
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
            Toast.makeText(a, "Разреши установку — и нажми «Скачать» ещё раз", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, "Настройки → Приложения → Установка неизвестных приложений", Toast.LENGTH_LONG).show();
        }
        return false;
    }

    /**
     * Сохраняет APK в общую папку «Загрузки».
     * Один APK — файлом pkg_vВерсия.apk; split — в подпапку RuStoreDL/pkg_vВерсия/.
     * Android 10+: через MediaStore (без разрешений). Android 8–9: напрямую (нужно WRITE_EXTERNAL_STORAGE).
     * Возвращает идентификаторы (content: URI или path:...) для последующего удаления.
     */
    static JSONArray saveToDownloads(Context ctx, String pkgName, String ver, List<File> apks) throws Exception {
        JSONArray saved = new JSONArray();
        String safe = pkgName.replaceAll("[^A-Za-z0-9._-]", "_");
        boolean split = apks.size() > 1;
        String subdir = "RuStoreDL/" + safe + "_v" + ver;
        for (File f : apks) {
            String fname = split ? f.getName() : safe + "_v" + ver + ".apk";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fname);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive");
                if (split) cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + subdir);
                Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("Не удалось создать файл в Загрузках");
                OutputStream o = ctx.getContentResolver().openOutputStream(uri);
                copy(new FileInputStream(f), o);
                o.close();
                saved.put(uri.toString());
            } else {
                File dir = split
                        ? new File(Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS), subdir)
                        : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                dir.mkdirs();
                File out = new File(dir, fname);
                copy(new FileInputStream(f), new FileOutputStream(out));
                saved.put("path:" + out.getAbsolutePath());
            }
        }
        return saved;
    }

    void setDlStatus(final String s) {
        ui.post(() -> {
            dlStatusTv.setText(s);
            dlStatusTv.setVisibility(View.VISIBLE);
        });
    }

    void setStatus(final String s) {
        ui.post(() -> statusTv.setText(s));
    }

    static void install(Context ctx, List<File> apks) throws Exception {
        install(ctx, apks, null, null);
    }

    /** Установка через системный PackageInstaller (поддерживает split-APK).
     *  pkg/ver едут в колбэк InstallReceiver — для автоочистки после установки. */
    static void install(Context ctx, List<File> apks, String pkg, String ver) throws Exception {
        android.util.Log.d("RuStoreOTA", "install pkg=" + pkg + " ver=" + ver + " files=" + apks.size() + " ctx=" + ctx.getPackageName());
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        // Важно для Android 10+/HyperOS — явно указываем чей пакет ставим
        try {
            String target = pkg != null ? pkg : ctx.getPackageName();
            sp.setAppPackageName(target);
            android.util.Log.d("RuStoreOTA", "setAppPackageName=" + target);
        } catch (Throwable ignore) {}
        // на Android 12+ можно явно потребовать действие пользователя (дефолт и так required)
        try {
            if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        } catch (Throwable ignore) {}
        int id = pi.createSession(sp);
        android.util.Log.d("RuStoreOTA", "session created id=" + id);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            for (File f : apks) {
                android.util.Log.d("RuStoreOTA", "write " + f.getName() + " len=" + f.length());
                OutputStream o = s.openWrite(f.getName(), 0, f.length());
                InputStream in = new FileInputStream(f);
                copy(in, o);
                in.close();
                s.fsync(o);
                o.close();
            }
            Intent cb = new Intent(ctx, InstallReceiver.class).setAction("ru.rustoredl.STATUS");
            if (pkg != null) cb.putExtra("pkg", pkg).putExtra("ver", ver == null ? "" : ver);
            // Для самообновления шлём pkg текущего приложения, чтобы InstallReceiver мог логировать
            else cb.putExtra("pkg", ctx.getPackageName()).putExtra("ver", ver == null ? "" : ver);
            cb.putExtra("_ts", System.currentTimeMillis());
            // FLAG_MUTABLE обязателен с Android 12: система дописывает статус в intent
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getBroadcast(ctx, 1, cb, flags);
            android.util.Log.d("RuStoreOTA", "commit session " + id + " -> InstallReceiver");
            s.commit(pending.getIntentSender());
            android.util.Log.d("RuStoreOTA", "commit OK");
        } finally {
            s.close();
        }
    }

    static List<File> extractApks(Context ctx, File zip) throws Exception {
        File dir = new File(ctx.getCacheDir(), "install");
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        List<File> out = new ArrayList<>();
        ZipFile z = new ZipFile(zip);
        try {
            int n = 0;
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                if (!e.getName().toLowerCase().endsWith(".apk")) continue;
                File f = new File(dir, (out.isEmpty() ? "base" : "split" + (++n)) + ".apk");
                InputStream in = z.getInputStream(e);
                FileOutputStream fo = new FileOutputStream(f);
                copy(in, fo);
                fo.close();
                in.close();
                out.add(f);
            }
        } finally {
            z.close();
        }
        return out;
    }

    /** Резервный одноканальный путь (сервер без Range или мелкий файл). */
    static void downloadSingle(String url, File part, DlUi ui) throws Exception {
        byte[] buf = new byte[65536];
        long lastUi = 0;
        int fails = 0;
        while (true) {
            awaitResume();
            long resumeFrom = part.exists() ? part.length() : 0;
            // установка соединения с ретраями: DNS/сеть могут моргнуть
            // (Unable to resolve host) — это не повод валить загрузку
            HttpURLConnection c;
            int code;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                SecurityUtil.applyTo(c);
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                if (resumeFrom > 0) c.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
                code = c.getResponseCode();
            } catch (Exception e) {
                if (++fails > 4) throw e;
                ui.status("Сеть пропала, переподключаюсь…");
                try { Thread.sleep(1500L * fails); } catch (InterruptedException ie) {}
                continue;
            }
            fails = 0;
            if (code >= 400) throw new Exception(
                    code == 403 || code == 404 ? "ссылка устарела — нажми «Скачать» заново" : "HTTP " + code + " при скачивании");
            boolean partial = code == 206;
            long total = c.getContentLength();
            if (total > 0) dlTotal = partial ? total + resumeFrom : total;
            if (!partial && resumeFrom > 0) { // сервер не умеет Range — начнём файл заново
                resumeFrom = 0;
                dlDone.set(0);
                dlTotal = total > 0 ? total : -1;
            }
            if (dlDone.get() < resumeFrom) dlDone.set(resumeFrom);
            InputStream in = c.getInputStream();
            FileOutputStream fo = new FileOutputStream(part, partial);
            boolean stopped = false;
            try {
                int r;
                while ((r = in.read(buf)) > 0) {
                    if (dlCancelled) throw new Cancelled();
                    if (dlPaused) { stopped = true; break; }
                    fo.write(buf, 0, r);
                    dlDone.addAndGet(r);
                    long now = System.currentTimeMillis();
                    if (now - lastUi > 300) { lastUi = now; updateDlProgress(ui); }
                }
            } finally {
                fo.close();
                in.close();
                c.disconnect();
            }
            if (stopped) continue;
            updateDlProgress(ui);
            return;
        }
    }

    void download(String url, File to) throws Exception {
        // простой вариант без управления (не используется основным потоком загрузки)
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        SecurityUtil.applyTo(c); // TLS-pinning
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("HTTP " + code + " при скачивании");
        InputStream in = c.getInputStream();
        FileOutputStream fo = new FileOutputStream(to);
        copy(in, fo);
        fo.close();
        in.close();
    }

    // ---------------------------------------------------------- утилиты

    static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
    }

    InputStream openStream(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        return c.getInputStream();
    }

    String parsePkg(String q) {
        Matcher m = P_LINK.matcher(q);
        if (m.find()) return m.group(1);
        String t = q.trim();
        if (P_PKG.matcher(t).matches()) return t;
        return null;
    }

    // ---------------------------------------------------------- API RuStore

    /**
     * Запрос к API. Автоподбор кода версии: RuStore отвечает
     * 417 (версия клиента слишком старая) или 419 (слишком новая),
     * 400 (заголовка нет/не число). При таком ответе один раз запускается
     * подбор нового значения, оно сохраняется и запрос повторяется.
     */
    JSONObject api(String method, String url, JSONObject body) throws Exception {
        return RuStoreApi.request(this, method, url, body, s -> setStatus(s));
    }

    static String human(long n) {
        if (n <= 0) return "0 МБ";
        return String.format("%.1f МБ", n / 1048576.0);
    }
}
