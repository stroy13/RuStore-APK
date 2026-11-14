#!/usr/bin/env bash
# ============================================================
# Сборка RuStoreDL.apk без Android Studio / Gradle.
# Нужно: JDK (javac, keytool, jar) + build-tools + android.jar.
# Опционально: /tmp/r8.jar — обфускация (иначе fallback на d8).
#
#   SDK=/tmp/sdk bash build.sh
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

SDK="${SDK:-/tmp/sdk}"
R8JAR="${R8JAR:-/tmp/r8.jar}"

# keytool может лежать рядом с javac, но не в PATH
JDKBIN="$(dirname "$(readlink -f "$(command -v javac)")")"
export PATH="$JDKBIN:$PATH"
[ -d "$SDK" ] || { echo "ОШИБКА: SDK не найден: $SDK (скачай build-tools и platform, см. README)"; exit 1; }
AAPT2="$(find "$SDK" -type f -name aapt2 | sort -V | tail -1)"
BT="${AAPT2%/aapt2}"
PLAT="$(find "$SDK" -type f -name android.jar | sort -V | tail -1)"  # самая свежая платформа
[ -n "$AAPT2" ] && [ -n "$PLAT" ] || { echo "ОШИБКА: в $SDK нет aapt2/android.jar"; exit 1; }
echo "build-tools: $BT"
echo "platform:    $PLAT"

# ---- ключ подписи.
# СВОЙ КЛЮЧ:   KS=/путь/my.keystore KS_PASS=пароль [KS_ALIAS=алиас] bash build.sh
# По умолчанию: release.keystore (пароль в keystore.info), иначе debug.keystore
KS="${KS:-}"
KS_PASS="${KS_PASS:-}"
KS_ALIAS="${KS_ALIAS:-}"
if [ -z "$KS" ]; then
  if [ -f release.keystore ]; then
    KS=release.keystore
    KS_PASS="$(sed -n 's/^RUSTOREDLPASS=//p' keystore.info)"
  else
    KS=debug.keystore
    KS_PASS=android
    if [ ! -f "$KS" ]; then
      echo "Создаю локальный debug-ключ (только для разработки)"
      keytool -genkeypair -v \
        -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null
    fi
  fi
fi
[ -f "$KS" ] || { echo "ОШИБКА: keystore не найден: $KS"; exit 1; }
# алиас не задан — берём первый PrivateKeyEntry из хранилища
if [ -z "$KS_ALIAS" ]; then
  KS_ALIAS="$(keytool -list -keystore "$KS" -storepass "$KS_PASS" 2>/dev/null \
    | sed -n 's/^\([^,]*\),.*PrivateKeyEntry.*/\1/p' | head -1)"
fi
[ -n "$KS_ALIAS" ] || { echo "ОШИБКА: не нашёл алиас в $KS (укажи KS_ALIAS=...)"; exit 1; }
echo "ключ подписи: $KS (алиас: $KS_ALIAS)"

# ---- синхронизация ожидаемого SHA-256 подписи (SecurityUtil сверяет при запуске)
# NO_SIG_CHECK=1 — отключить проверку подписи внутри приложения
# (нужно, если планируешь переподписывать готовый APK другим ключом)
if [ "${NO_SIG_CHECK:-0}" = "1" ]; then
  CERT_HEX="SKIP"
  echo "!! проверка подписи ВНУТРИ приложения ОТКЛЮЧЕНА (NO_SIG_CHECK=1)"
else
  CERT_HEX="$(keytool -list -v -keystore "$KS" -storepass "$KS_PASS" -alias "$KS_ALIAS" 2>/dev/null \
    | grep -m1 'SHA256:' | sed 's/.*SHA256: //; s/[: ]//g' | tr 'A-F' 'a-f')"
  [ -n "$CERT_HEX" ] || { echo "ОШИБКА: не извлёк SHA-256 сертификата из $KS"; exit 1; }
  echo "подпись SHA-256: $CERT_HEX"
fi
cat > res/values/security.xml <<EOF
<?xml version="1.0" encoding="utf-8"?>
<!-- Автогенерация build.sh: ожидаемый SHA-256 сертификата подписи -->
<resources>
    <string name="cert_sha256" translatable="false">$CERT_HEX</string>
</resources>
EOF
echo "подпись SHA-256: $CERT_HEX"

rm -rf gen obj
mkdir -p gen obj/dex obj/classes

echo "==> иконка"
python3 make_icon.py

echo "==> ресурсы"
"$BT/aapt2" compile --dir res -o obj/res.zip
"$BT/aapt2" link -o obj/base.apk -I "$PLAT" \
    --manifest AndroidManifest.xml -R obj/res.zip \
    --java gen --auto-add-overlay

echo "==> java → классы"
javac -encoding UTF-8 -source 1.8 -target 1.8 -Xlint:none -cp "$PLAT" -d obj/classes \
    gen/ru/rustoredl/R.java java/ru/rustoredl/*.java

echo "==> R8: минификация + обфускация → dex"
rm -rf obj/dex && mkdir -p obj/dex
if [ -f "$R8JAR" ]; then
  jar cf obj/prog.jar -C obj/classes .
  java -cp "$R8JAR" com.android.tools.r8.R8 --release \
      --lib "$PLAT" --min-api 26 \
      --pg-conf proguard-rules.pro \
      --pg-map-output obj/mapping.txt \
      --output obj/dex obj/prog.jar
  cp obj/mapping.txt mapping.txt
  echo "    карта переименований: mapping.txt"
else
  echo "    R8 не найден ($R8JAR) — fallback на d8 БЕЗ обфускации"
  "$BT/d8" --release --lib "$PLAT" --min-api 26 --output obj/dex \
      $(find obj/classes -name '*.class')
fi

echo "==> упаковка"
cp obj/base.apk obj/unsigned.apk
python3 - <<'EOF'
import zipfile
with zipfile.ZipFile("obj/unsigned.apk", "a") as z:
    z.write("obj/dex/classes.dex", "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
print("classes.dex добавлен")
EOF

"$BT/zipalign" -f 4 obj/unsigned.apk obj/aligned.apk

echo "==> подпись (v1+v2+v3+v4)"
"$BT/apksigner" sign \
    --ks "$KS" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --v4-signing-enabled true \
    --out RuStoreDL.apk obj/aligned.apk
"$BT/apksigner" verify --print-certs RuStoreDL.apk | head -3

echo ""
echo "Готово: $(pwd)/RuStoreDL.apk"
