package ru.rustoredl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Список исключений из автообновления: приложения, которые AutoUpdater
 * не трогает (не скачивает и не устанавливает). Хранится в prefs «cfg» /
 * «autoExclusions» как JSON-массив пакетов. Управление: окно в Настройках;
 * добавление — долгий тап по приложению в «Обновлениях».
 */
public final class Exclusions {

    private Exclusions() {}

    // ---------------------------------------------------------- хранилище

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    /** Снимок списка (отсортирован по пакету). */
    static List<String> all(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p(c).getString("autoExclusions", "[]"));
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignore) {}
        Collections.sort(out);
        return out;
    }

    static void save(Context c, List<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        p(c).edit().putString("autoExclusions", a.toString()).apply();
    }

    /** Входит ли пакет в исключения (для AutoUpdater). */
    public static boolean isExcluded(Context c, String pkg) {
        return all(c).contains(pkg);
    }

    /** Добавить; false — уже был в списке. */
    static boolean add(Context c, String pkg) {
        List<String> l = all(c);
        if (l.contains(pkg)) return false;
        l.add(pkg);
        save(c, l);
        return true;
    }

    static boolean remove(Context c, String pkg) {
        List<String> l = all(c);
        boolean ok = l.remove(pkg);
        if (ok) save(c, l);
        return ok;
    }

    // ---------------------------------------------------------- скрытые из списка обновлений
    private static final String KEY_HIDDEN = "hiddenUpdates";

    static List<String> hiddenAll(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p(c).getString(KEY_HIDDEN, "[]"));
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignore) {}
        Collections.sort(out);
        return out;
    }

    static void saveHidden(Context c, List<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        p(c).edit().putString(KEY_HIDDEN, a.toString()).apply();
    }

    public static boolean isHidden(Context c, String pkg) {
        return hiddenAll(c).contains(pkg);
    }

    static boolean addHidden(Context c, String pkg) {
        List<String> l = hiddenAll(c);
        if (l.contains(pkg)) return false;
        l.add(pkg);
        saveHidden(c, l);
        return true;
    }

    static boolean removeHidden(Context c, String pkg) {
        List<String> l = hiddenAll(c);
        boolean ok = l.remove(pkg);
        if (ok) saveHidden(c, l);
        return ok;
    }

    public static void showHiddenDialog(final Activity a) {
        final List<String> data = hiddenAll(a);
        View v = LayoutInflater.from(a).inflate(R.layout.dialog_exclusions, null);
        final TextView empty = v.findViewById(R.id.empty);
        final ListView list = v.findViewById(R.id.list);
        final BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return data.size(); }
            @Override public Object getItem(int i) { return data.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override
            public View getView(int i, View cv, ViewGroup parent) {
                View row = cv != null ? cv : LayoutInflater.from(a).inflate(R.layout.item_exclusion, parent, false);
                TextView name = row.findViewById(R.id.appName);
                TextView pkg = row.findViewById(R.id.appPkg);
                String pn = data.get(i);
                String label = pn;
                try {
                    label = String.valueOf(a.getPackageManager().getApplicationLabel(a.getPackageManager().getApplicationInfo(pn, 0)));
                } catch (Exception ignore) {}
                name.setText(label);
                pkg.setText(pn);
                return row;
            }
        };
        list.setAdapter(ad);
        empty.setText(data.isEmpty() ? "Скрытых нет — долгий тап по приложению в «Обновлениях» → «Скрыть из списка»" : "");
        empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("Скрытые из списка обновлений")
                .setView(v)
                .setPositiveButton("Готово", null)
                .setNeutralButton("Добавить приложение", (di, wi) -> showHiddenPicker(a))
                .create();
        list.setOnItemClickListener((parent, view, i, id) -> {
            final String pn = data.get(i);
            String label = ((TextView) view.findViewById(R.id.appName)).getText().toString();
            new AlertDialog.Builder(a)
                    .setTitle(label)
                    .setMessage("Показать снова в списке обновлений?")
                    .setPositiveButton("Показать", (di, wi) -> {
                        removeHidden(a, pn);
                        data.remove(pn);
                        ad.notifyDataSetChanged();
                        empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
                        Toast.makeText(a, "Возвращено в список: " + label, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Оставить скрытым", null)
                    .show();
        });
        d.show();
    }

    private static void showHiddenPicker(final Activity a) {
        final PackageManager pm = a.getPackageManager();
        final List<PackageInfo> apps = new ArrayList<>();
        try {
            for (PackageInfo pi : pm.getInstalledPackages(0)) {
                if (pi.applicationInfo == null) continue;
                if ((pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (a.getPackageName().equals(pi.packageName)) continue;
                apps.add(pi);
            }
        } catch (Exception ignore) {}
        Collections.sort(apps, (x, y) -> String.valueOf(x.applicationInfo.loadLabel(pm)).compareToIgnoreCase(String.valueOf(y.applicationInfo.loadLabel(pm))));
        View v = LayoutInflater.from(a).inflate(R.layout.dialog_pick_app, null);
        final android.widget.EditText search = v.findViewById(R.id.search);
        final ListView list = v.findViewById(R.id.list);
        final android.widget.TextView empty = v.findViewById(android.R.id.empty);
        final List<PackageInfo> shown = new ArrayList<>(apps);
        final BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override
            public View getView(int i, View cv, ViewGroup parent) {
                View row = cv != null ? cv : LayoutInflater.from(a).inflate(R.layout.item_pick_app, parent, false);
                PackageInfo pi = shown.get(i);
                android.widget.ImageView icon = row.findViewById(R.id.icon);
                TextView name = row.findViewById(R.id.name);
                TextView pkg = row.findViewById(R.id.pkg);
                try { icon.setImageDrawable(pm.getApplicationIcon(pi.packageName)); } catch (Exception ignore) {}
                name.setText(String.valueOf(pi.applicationInfo.loadLabel(pm)));
                pkg.setText(pi.packageName);
                if (isHidden(a, pi.packageName)) name.setText(name.getText() + "  ✔ скрыто");
                return row;
            }
        };
        list.setAdapter(ad);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence cs, int x, int y, int z) {}
            public void onTextChanged(CharSequence cs, int x, int y, int z) {}
            public void afterTextChanged(android.text.Editable e) {
                String q = e.toString().trim().toLowerCase();
                shown.clear();
                for (PackageInfo pi : apps) {
                    if (q.isEmpty() || String.valueOf(pi.applicationInfo.loadLabel(pm)).toLowerCase().contains(q) || pi.packageName.toLowerCase().contains(q)) shown.add(pi);
                }
                ad.notifyDataSetChanged();
                empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("Скрыть приложение")
                .setView(v)
                .setNegativeButton("Отмена", (di, wi) -> showHiddenDialog(a))
                .create();
        list.setOnItemClickListener((parent, view, i, id) -> {
            PackageInfo pi = shown.get(i);
            String label = String.valueOf(pi.applicationInfo.loadLabel(pm));
            if (isHidden(a, pi.packageName)) {
                Toast.makeText(a, "«" + label + "» уже скрыто", Toast.LENGTH_SHORT).show();
            } else {
                addHidden(a, pi.packageName);
                Toast.makeText(a, "Скрыто: " + label, Toast.LENGTH_SHORT).show();
            }
            d.dismiss();
            showHiddenDialog(a);
        });
        d.show();
    }

    // ---------------------------------------------------------- окно в настройках

    public static void showDialog(final Activity a) {
        final List<String> data = all(a);
        View v = LayoutInflater.from(a).inflate(R.layout.dialog_exclusions, null);
        final TextView empty = v.findViewById(R.id.empty);
        final ListView list = v.findViewById(R.id.list);
        final AlertDialog[] dlg = new AlertDialog[1];

        final BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return data.size(); }
            @Override public Object getItem(int i) { return data.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override
            public View getView(int i, View cv, ViewGroup parent) {
                View row = cv != null ? cv : LayoutInflater.from(a)
                        .inflate(R.layout.item_exclusion, parent, false);
                TextView name = row.findViewById(R.id.appName);
                TextView pkg = row.findViewById(R.id.appPkg);
                String pn = data.get(i);
                String label = pn;
                try {
                    label = String.valueOf(a.getPackageManager()
                            .getApplicationLabel(
                                    a.getPackageManager().getApplicationInfo(pn, 0)));
                } catch (Exception ignore) {}
                name.setText(label);
                pkg.setText(pn);
                return row;
            }
        };
        list.setAdapter(ad);
        empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);

        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("Исключения из автообновления")
                .setView(v)
                .setPositiveButton("Готово", null)
                .setNeutralButton("Добавить приложение", (di, wi) -> showPicker(a))
                .create();
        dlg[0] = d;

        list.setOnItemClickListener((parent, view, i, id) -> {
            final String pn = data.get(i);
            String label = ((TextView) view.findViewById(R.id.appName)).getText().toString();
            new AlertDialog.Builder(a)
                    .setTitle(label)
                    .setMessage("Убрать из исключений? Автообновление снова будет обновлять это приложение.")
                    .setPositiveButton("Убрать", (di, wi) -> {
                        remove(a, pn);
                        data.remove(pn);
                        ad.notifyDataSetChanged();
                        empty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
                        Toast.makeText(a, "Убрано: " + label, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Оставить", null)
                    .show();
        });

        d.show();
    }

    /** Выбор приложения из установленных: список с иконками, названиями, поиском. */
    private static void showPicker(final Activity a) {
        final PackageManager pm = a.getPackageManager();
        final List<PackageInfo> apps = new ArrayList<>();
        try {
            for (PackageInfo pi : pm.getInstalledPackages(0)) {
                if (pi.applicationInfo == null) continue;
                if ((pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                if (a.getPackageName().equals(pi.packageName)) continue;
                apps.add(pi);
            }
        } catch (Exception ignore) {}
        Collections.sort(apps, (x, y) -> String.valueOf(
                x.applicationInfo.loadLabel(pm)).compareToIgnoreCase(
                String.valueOf(y.applicationInfo.loadLabel(pm))));

        View v = LayoutInflater.from(a).inflate(R.layout.dialog_pick_app, null);
        final android.widget.EditText search = v.findViewById(R.id.search);
        final ListView list = v.findViewById(R.id.list);
        final android.widget.TextView empty = v.findViewById(android.R.id.empty);

        final List<PackageInfo> shown = new ArrayList<>(apps);
        final BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override
            public View getView(int i, View cv, ViewGroup parent) {
                View row = cv != null ? cv : LayoutInflater.from(a)
                        .inflate(R.layout.item_pick_app, parent, false);
                PackageInfo pi = shown.get(i);
                android.widget.ImageView icon = row.findViewById(R.id.icon);
                TextView name = row.findViewById(R.id.name);
                TextView pkg = row.findViewById(R.id.pkg);
                try { icon.setImageDrawable(pm.getApplicationIcon(pi.packageName)); }
                catch (Exception ignore) {}
                name.setText(String.valueOf(pi.applicationInfo.loadLabel(pm)));
                pkg.setText(pi.packageName);
                if (isExcluded(a, pi.packageName))
                    name.setText(name.getText() + "  ✔");
                return row;
            }
        };
        list.setAdapter(ad);

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence cs, int x, int y, int z) {}
            public void onTextChanged(CharSequence cs, int x, int y, int z) {}
            public void afterTextChanged(android.text.Editable e) {
                String q = e.toString().trim().toLowerCase();
                shown.clear();
                for (PackageInfo pi : apps) {
                    if (q.isEmpty()
                            || String.valueOf(pi.applicationInfo.loadLabel(pm)).toLowerCase().contains(q)
                            || pi.packageName.toLowerCase().contains(q)) {
                        shown.add(pi);
                    }
                }
                ad.notifyDataSetChanged();
                empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });

        final AlertDialog[] dlg = new AlertDialog[1];
        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("Выбери приложение")
                .setView(v)
                .setNegativeButton("Отмена", (di, wi) -> showDialog(a))
                .create();
        dlg[0] = d;
        list.setOnItemClickListener((parent, view, i, id) -> {
            PackageInfo pi = shown.get(i);
            String label = String.valueOf(pi.applicationInfo.loadLabel(pm));
            if (isExcluded(a, pi.packageName)) {
                Toast.makeText(a, "«" + label + "» уже в исключениях", Toast.LENGTH_SHORT).show();
            } else {
                add(a, pi.packageName);
                Toast.makeText(a, "Исключено: " + label, Toast.LENGTH_SHORT).show();
            }
            d.dismiss();
            showDialog(a); // вернуться к списку исключений с обновлением
        });
        d.show();
    }
}
