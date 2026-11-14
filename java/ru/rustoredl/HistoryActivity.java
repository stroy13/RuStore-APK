package ru.rustoredl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * История скачиваний: список всего, что качалось, повторная установка
 * из кэша, удаление файлов (кэш + копии в Загрузках) и полная очистка.
 */
public class HistoryActivity extends Activity {

    JSONArray data = new JSONArray();
    MyAdapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF0B0E13);
        getWindow().setNavigationBarColor(0xFF0B0E13);
        setContentView(R.layout.activity_history);
        adapter = new MyAdapter();
        ((ListView) findViewById(R.id.list)).setAdapter(adapter);
        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.clearAll).setOnClickListener(v -> confirmClearAll());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    void reload() {
        data = load(this);
        adapter.notifyDataSetChanged();
        long[] ci = cacheInfo(this);
        ((TextView) findViewById(R.id.cacheInfo)).setText(data.length() == 0
                ? "Здесь появится всё, что ты скачивал"
                : "Записей: " + data.length() + "   •   в кэше: " + ci[0] + " шт (" + MainActivity.human(ci[1]) + ")");
        findViewById(R.id.clearAll).setEnabled(data.length() > 0);
    }

    // ---------------------------------------------------------- действия

    void tryInstall(List<File> fs, JSONObject rec) {
        if (!MainActivity.ensureInstallPermission(this)) return;
        installFiles(this, fs, rec);
    }

    /** Установка файлов записи; static — доступно из диалогов «уже скачано». */
    static void installFiles(final Activity a, List<File> fs, JSONObject rec) {
        Toast.makeText(a, "Запускаю установку…", Toast.LENGTH_SHORT).show();
        final String pkg = rec == null ? null : rec.optString("pkg", null);
        final String ver = rec == null ? null : rec.optString("version", null);
        new Thread(() -> {
            try {
                MainActivity.install(a, fs, pkg, ver);
            } catch (Exception e) {
                a.runOnUiThread(() -> Toast.makeText(a, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /** Свежая запись этого pkg+version с живыми файлами — для «уже скачано». */
    static JSONObject findRecord(Context c, String pkg, String ver) {
        if (pkg == null || ver == null) return null;
        JSONArray d = load(c);
        for (int i = 0; i < d.length(); i++) {
            JSONObject r = d.optJSONObject(i);
            if (r == null || !pkg.equals(r.optString("pkg"))
                    || !ver.equals(r.optString("version"))) continue;
            if (filesExist(recordFiles(c, r))) return r;
        }
        return null;
    }

    /** Автоочистка: удаляет файлы (кэш + копии) всех записей pkg+version. */
    static void cleanVersion(final Context c, final String pkg, final String ver) {
        new Thread(() -> {
            JSONArray d = load(c);
            for (int i = 0; i < d.length(); i++) {
                JSONObject r = d.optJSONObject(i);
                if (r == null || !pkg.equals(r.optString("pkg"))) continue;
                if (ver != null && !ver.isEmpty() && !ver.equals(r.optString("version"))) continue;
                deleteRecordFiles(c, r);
            }
        }).start();
    }

    /** Удаляет запись и её файлы (кэш + копии в Загрузках). */
    void deleteAt(int i) {
        try {
            JSONObject rec = data.getJSONObject(i);
            deleteRecordFiles(this, rec);
            JSONArray n = new JSONArray();
            for (int j = 0; j < data.length(); j++) if (j != i) n.put(data.get(j));
            data = n;
            persist(this, n);
            reload();
            toast("Запись и файлы удалены");
        } catch (Exception ignore) {}
    }

    /** Удаляет только файлы записи; сама запись остаётся в истории. */
    void deleteFilesOnly(int i) {
        JSONObject rec = data.optJSONObject(i);
        if (rec == null) return;
        deleteRecordFiles(this, rec);
        reload();
        toast("Файлы удалены");
    }

    void confirmClearAll() {
        new AlertDialog.Builder(this)
                .setTitle("Очистить всё?")
                .setMessage("Будут удалены: APK из кэша приложения, копии из «Загрузок» и вся история скачиваний.")
                .setPositiveButton("Очистить", (d, w) -> {
                    for (int i = 0; i < data.length(); i++) deleteRecordFiles(this, data.optJSONObject(i));
                    data = new JSONArray();
                    persist(this, data);
                    reload();
                    toast("Очищено");
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    void rowDialog(int i) {
        JSONObject rec = data.optJSONObject(i);
        if (rec == null) return;
        List<File> fs = recordFiles(this, rec);
        boolean canInstall = filesExist(fs);
        List<String> opts = new ArrayList<>();
        if (canInstall) opts.add("Установить снова");
        opts.add("Удалить файлы (кэш и копия в Загрузках)");
        opts.add("Удалить запись целиком");
        opts.add("Отмена");
        new AlertDialog.Builder(this)
                .setTitle(rec.optString("name", rec.optString("pkg")))
                .setItems(opts.toArray(new String[0]), (d, w) -> {
                    String o = opts.get(w);
                    if (o.startsWith("Установить")) tryInstall(fs, rec);
                    else if (o.startsWith("Удалить файлы")) deleteFilesOnly(i);
                    else if (o.startsWith("Удалить запись")) deleteAt(i);
                })
                .show();
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------- хранилище истории

    static File histFile(Context c) {
        return new File(c.getFilesDir(), "history.json");
    }

    static synchronized JSONArray load(Context c) {
        try {
            File f = histFile(c);
            if (!f.exists()) return new JSONArray();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return new JSONArray(CryptoUtil.unprotect(sb.toString()));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static synchronized void persist(Context c, JSONArray a) {
        try {
            FileOutputStream o = new FileOutputStream(histFile(c));
            o.write(CryptoUtil.protect(a.toString()).getBytes("UTF-8"));
            o.close();
        } catch (Exception ignore) {}
    }

    /** Добавляет запись в начало списка (новые сверху). */
    static synchronized void addRecord(Context c, String pkg, String name, String ver,
                                       List<File> apks, JSONArray saved) {
        try {
            JSONObject rec = new JSONObject();
            rec.put("pkg", pkg);
            rec.put("name", name);
            rec.put("version", ver);
            rec.put("time", System.currentTimeMillis());
            rec.put("size", filesSize(apks));
            JSONArray files = new JSONArray();
            for (File f : apks) files.put(f.getName());
            rec.put("files", files);
            rec.put("saved", saved == null ? new JSONArray() : saved);
            JSONArray old = load(c);
            JSONArray n = new JSONArray();
            n.put(rec);
            for (int i = 0; i < old.length(); i++) n.put(old.get(i));
            persist(c, n);
        } catch (Exception ignore) {}
    }

    // ---------------------------------------------------------- файлы записи

    static List<File> recordFiles(Context c, JSONObject rec) {
        List<File> out = new ArrayList<>();
        JSONArray files = rec.optJSONArray("files");
        File dir = new File(c.getCacheDir(), "install");
        if (files != null)
            for (int i = 0; i < files.length(); i++) out.add(new File(dir, files.optString(i)));
        return out;
    }

    static boolean filesExist(List<File> fs) {
        if (fs.isEmpty()) return false;
        for (File f : fs) if (!f.exists()) return false;
        return true;
    }

    static long filesSize(List<File> fs) {
        long s = 0;
        for (File f : fs) if (f.exists()) s += f.length();
        return s;
    }

    static boolean hasSaved(JSONObject rec) {
        JSONArray saved = rec.optJSONArray("saved");
        return saved != null && saved.length() > 0;
    }

    /** [кол-во файлов в кэше, суммарный размер] по всем записям. */
    static long[] cacheInfo(Context c) {
        File dir = new File(c.getCacheDir(), "install");
        File[] ls = dir.listFiles();
        long n = 0, b = 0;
        if (ls != null) for (File f : ls) { n++; b += f.length(); }
        return new long[]{n, b};
    }

    /** Удаляет файлы записи: из кэша и копии из Загрузок (content: URI или path:). */
    static void deleteRecordFiles(Context c, JSONObject rec) {
        for (File f : recordFiles(c, rec)) f.delete();
        JSONArray saved = rec.optJSONArray("saved");
        if (saved == null) return;
        for (int i = 0; i < saved.length(); i++) {
            String id = saved.optString(i);
            try {
                if (id.startsWith("content:")) {
                    c.getContentResolver().delete(Uri.parse(id), null, null);
                } else if (id.startsWith("path:")) {
                    new File(id.substring(5)).delete();
                }
            } catch (Exception ignore) {}
        }
    }

    // ---------------------------------------------------------- адаптер списка

    class MyAdapter extends BaseAdapter {
        @Override public int getCount() { return data.length(); }
        @Override public Object getItem(int i) { return data.optJSONObject(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            View v = cv == null
                    ? getLayoutInflater().inflate(R.layout.item_history, parent, false)
                    : cv;
            JSONObject rec = data.optJSONObject(i);
            List<File> fs = recordFiles(HistoryActivity.this, rec);
            boolean have = filesExist(fs);

            String nm = rec.optString("name", rec.optString("pkg"));
            ((TextView) v.findViewById(R.id.hAvatar)).setText(
                    nm.isEmpty() ? "?" : nm.substring(0, 1).toUpperCase());
            ((TextView) v.findViewById(R.id.hName)).setText(nm);
            ((TextView) v.findViewById(R.id.hSub)).setText(rec.optString("pkg")
                    + "  •  v" + rec.optString("version")
                    + "  •  " + MainActivity.human(rec.optLong("size", 0))
                    + "  •  " + fmt(rec.optLong("time", 0)));

            TextView st = v.findViewById(R.id.hState);
            if (have) {
                st.setText("в кэше: " + MainActivity.human(filesSize(fs))
                        + (hasSaved(rec) ? "  •  копия в Загрузках" : ""));
                st.setTextColor(0xFF8FD3A8);
            } else {
                st.setText(hasSaved(rec) ? "кэш удалён  •  копия в Загрузках" : "файлы удалены");
                st.setTextColor(0xFF6D7A93);
            }

            Button bi = v.findViewById(R.id.btnInstall);
            bi.setVisibility(have ? View.VISIBLE : View.GONE);
            bi.setOnClickListener(ev -> tryInstall(fs, rec));
            v.findViewById(R.id.btnDel).setOnClickListener(ev -> deleteAt(i));
            v.setOnClickListener(ev -> rowDialog(i));
            return v;
        }
    }

    static String fmt(long t) {
        return new SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()).format(new Date(t));
    }
}
