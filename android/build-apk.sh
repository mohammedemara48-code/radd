#!/bin/sh
set -eu
SDK=/tmp/android-sdk
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
ROOT=/workspace/android/Radd
OUT=/tmp/radd-build
rm -rf "$OUT"
mkdir -p "$OUT/classes"
"$BT/aapt2" compile --dir "$ROOT/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/linked.apk" -I "$PLATFORM" --manifest "$ROOT/AndroidManifest.xml" --java "$OUT/gen" "$OUT/res.zip"
find "$ROOT/java" -name '*.java' > "$OUT/sources.txt"
javac --release 17 -encoding UTF-8 -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"
"$BT/d8" --min-api 26 --lib "$PLATFORM" --output "$OUT" $(find "$OUT/classes" -name '*.class')
cp "$OUT/linked.apk" "$OUT/unsigned.apk"
python3 - <<'PY'
import zipfile
zipfile.ZipFile("/tmp/radd-build/unsigned.apk", "a").write("/tmp/radd-build/classes.dex", "classes.dex")
PY
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f "$OUT/radd.keystore" ]; then
  keytool -genkeypair -keystore "$OUT/radd.keystore" -storepass raddpass -keypass raddpass \
    -alias radd -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Radd, O=Radd, C=EG"
fi
"$BT/apksigner" sign --ks "$OUT/radd.keystore" --ks-pass pass:raddpass --key-pass pass:raddpass \
  --out /workspace/public/radd.apk "$OUT/aligned.apk"
"$BT/apksigner" verify /workspace/public/radd.apk
ls -lh /workspace/public/radd.apk
