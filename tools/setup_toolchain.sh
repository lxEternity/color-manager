#!/usr/bin/env bash
# Android 构建工具链安装（沙盒重置后重跑即可）
set -euo pipefail
SDK=/opt/android-sdk
J8=/usr/lib/jvm/java-8-openjdk-amd64
mkdir -p $SDK/build-tools $SDK/platforms
cd /tmp

command -v javac >/dev/null || { export DEBIAN_FRONTEND=noninteractive; apt-get update -qq; apt-get install -y -qq openjdk-11-jdk-headless >/dev/null; }
[ -x "$J8/bin/javac" ] || { export DEBIAN_FRONTEND=noninteractive; apt-get install -y -qq openjdk-8-jdk-headless >/dev/null; }

if [ ! -f "$SDK/platforms/android-34/android.jar" ]; then
  echo "--- platform 34 ---"
  curl -sSL --max-time 400 -o p34.zip https://dl.google.com/android/repository/platform-34-ext7_r03.zip
  unzip -qo p34.zip -d $SDK/platforms/
fi

if [ ! -x "$SDK/build-tools/34.0.0/aapt2" ]; then
  echo "--- build-tools 34 ---"
  curl -sSL --max-time 400 -o bt34.zip https://dl.google.com/android/repository/build-tools_r34-linux.zip
  unzip -qo bt34.zip -d $SDK/build-tools/
  mv $SDK/build-tools/android-14 $SDK/build-tools/34.0.0 2>/dev/null || true
fi

if [ ! -f "$SDK/r8.jar" ]; then
  echo "--- r8 ---"
  curl -sSL --max-time 60 https://dl.google.com/android/maven2/com/android/tools/r8/maven-metadata.xml -o r8meta.xml
  R8V=$(grep -oE "<version>[0-9.]+</version>" r8meta.xml | tail -1 | grep -oE "[0-9.]+")
  curl -sSL --max-time 180 -o $SDK/r8.jar "https://dl.google.com/android/maven2/com/android/tools/r8/$R8V/r8-$R8V.jar"
fi

echo "--- 校验 ---"
ls -la $SDK/platforms/android-34/android.jar $SDK/r8.jar
ls $SDK/build-tools/34.0.0/ | grep -E "^(aapt2|d8|zipalign|apksigner)$"
echo "TOOLCHAIN OK"
