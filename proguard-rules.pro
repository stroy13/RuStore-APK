# R8/ProGuard-правила RuStoreDL
# Все компоненты, объявленные в манифесте, система находит по имени —
# их НЕЛЬЗЯ переименовывать (иначе ActivityNotFoundException).
-keep class ru.rustoredl.MainActivity { <init>(); }
-keep class ru.rustoredl.HistoryActivity { <init>(); }
-keep class ru.rustoredl.SettingsActivity { <init>(); }
-keep class ru.rustoredl.InstallReceiver { <init>(); }
-keep class ru.rustoredl.App { <init>(); }

# Страховка на будущее: любой новый компонент манифеста не должен
# переименовываться, даже если забыли добавить в список выше.
-keep class * extends android.app.Activity { <init>(); }
-keep class * extends android.content.BroadcastReceiver { <init>(); }
-keep class * extends android.app.Service { <init>(); }
-keep class * extends android.content.ContentProvider { <init>(); }

# Всё остальное (включая SecurityUtil, CryptoUtil и служебные классы) —
# переименовывается и минифицируется. Карту переименований см. mapping.txt.
-dontwarn **
