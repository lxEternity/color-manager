#!/usr/bin/env bash
# ColorFC APP 一键构建（Vue → R8 混淆 → DEX 加壳 → 签名）
# 用法: tools/build_app.sh [versionCode] [versionName]
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=${ANDROID_SDK:-/opt/android-sdk}
BT=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
R8JAR=$SDK/r8.jar
APP=$ROOT/app/src/main
WAPP=$ROOT/webapp
OUT=$ROOT/build
VC=${1:-180}
VN=${2:-1.4.1}
OUTAPK=$OUT/ColorManager_v${VN}.apk
# JDK8：lambda 编译需 rt.jar 兜底（android.jar 不含 LambdaMetafactory）
J8=/usr/lib/jvm/java-8-openjdk-amd64
JAVAC=$J8/bin/javac
BOOTCP=$PLATFORM:$J8/jre/lib/rt.jar

[ -f "$PLATFORM" ] || { echo "缺 android.jar ($PLATFORM)"; exit 1; }
[ -f "$R8JAR" ] || { echo "缺 r8.jar ($R8JAR)"; exit 1; }
command -v java >/dev/null || { echo "缺 java"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/stub-classes" "$OUT/dex" "$OUT/assets/webapp"

echo "==> [1/8] Vue 构建"
(cd "$WAPP" && npm run build >/dev/null)
cp "$WAPP"/dist/* "$OUT/assets/webapp/"

echo "==> [2/8] aapt2 资源编译/链接 (versionCode=$VC versionName=$VN)"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$OUT/gen" -A "$OUT/assets" \
  --version-code "$VC" --version-name "$VN" \
  --min-sdk-version 26 --target-sdk-version 37 \
  --auto-add-overlay "$OUT/res.zip"

echo "==> [3/8] javac 业务代码"
find "$APP/java" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
# 编译错误必须终止构建（此前 || true 会吞掉错误产出残废 dex）
if ! "$JAVAC" -source 8 -target 8 -nowarn -bootclasspath "$BOOTCP" \
  -d "$OUT/classes" @"$OUT/sources.txt" 2> "$OUT/javac.log"; then
  cat "$OUT/javac.log"; echo "javac 失败"; exit 1
fi
grep -v "^Note" "$OUT/javac.log" || true

echo "==> [4/8] R8 混淆 → 业务 dex"
cat > "$OUT/rules.pro" <<'EOF'
# 系统按名字实例化的组件：类名与生命周期入口不可混淆
-keep class Color.fc.MainActivity { *; }
-keep class Color.fc.LockActivity { *; }
-keep class Color.fc.MonitorService { *; }
-keep class Color.fc.AppLimitService { *; }
-keep class Color.fc.BootReceiver { *; }
# JS 桥方法名（WebView 按字符串反射调用）
-keepclassmembers class Color.fc.NativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn **
EOF
java -cp "$R8JAR" com.android.tools.r8.R8 --release \
  --lib "$PLATFORM" --min-api 26 \
  --pg-conf "$OUT/rules.pro" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class")
[ -f "$OUT/dex/classes.dex" ] || { echo "R8 未产出 dex"; exit 1; }

echo "==> [5/8] DEX 加壳（AES-256-GCM → assets/cfc.dat）"
mkdir -p "$OUT/pack-classes"
"$JAVAC" -source 8 -target 8 -nowarn -d "$OUT/pack-classes" \
  "$ROOT/tools/pack/java/Color/fc/stub/PackTool.java" \
  "$APP/stub/java/Color/fc/stub/KeyBox.java"
java -cp "$OUT/pack-classes" Color.fc.stub.PackTool seal \
  "$OUT/dex/classes.dex" "$OUT/assets/cfc.dat"
java -cp "$OUT/pack-classes" Color.fc.stub.PackTool unseal \
  "$OUT/assets/cfc.dat" "$OUT/dex/verify.dex"
cmp -s "$OUT/dex/classes.dex" "$OUT/dex/verify.dex" || { echo "加壳 roundtrip 校验失败"; exit 1; }
echo "    roundtrip 校验通过"
# 组件完整性校验：manifest 引用的类必须全部在业务 dex 中（防 javac 静默丢类）
# 注意不用 grep -q：提前退出会让 strings 收 SIGPIPE，配合 pipefail 误报失败
DEX_STRS=$(strings "$OUT/dex/verify.dex")
for CLS in MainActivity LockActivity MonitorService AppLimitService BootReceiver; do
  echo "$DEX_STRS" | grep "LColor/fc/$CLS;" > /dev/null \
    || { echo "组件缺失: $CLS 未编入业务 dex"; exit 1; }
done
echo "    组件完整性校验通过"

echo "==> [6/8] 壳 dex（StubApp 明面）"
mkdir -p "$OUT/stub-dex"
find "$APP/stub/java" -name "*.java" > "$OUT/stub-sources.txt"
if ! "$JAVAC" -source 8 -target 8 -nowarn -bootclasspath "$BOOTCP" \
  -d "$OUT/stub-classes" @"$OUT/stub-sources.txt" 2> "$OUT/javac-stub.log"; then
  cat "$OUT/javac-stub.log"; echo "壳 javac 失败"; exit 1
fi
"$BT/d8" --release --lib "$PLATFORM" --min-api 26 \
  --output "$OUT/stub-dex" $(find "$OUT/stub-classes" -name "*.class")

echo "==> [7/8] 组装 APK"
cd "$OUT/stub-dex" && zip -q "$OUT/base.apk" classes.dex
# cfc.dat 在步骤 5 才生成，aapt2 link 时不存在 → 此处补入（保留 assets/ 路径）
cd "$OUT" && zip -q base.apk assets/cfc.dat
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/unsigned.apk"

echo "==> [8/8] 签名"
"$BT/apksigner" sign --ks "$ROOT/color.jks" --ks-key-alias color \
  --ks-pass pass:colorfc123 --key-pass pass:colorfc123 \
  --out "$OUTAPK" "$OUT/unsigned.apk"
"$BT/apksigner" verify --print-certs "$OUTAPK" | head -3
ls -la "$OUTAPK"
echo "完成: $OUTAPK"
