#!/usr/bin/env bash
# Listen（Android 端命令行）安装脚本：把 listen 装到 ~/.local/bin。
#
# 用法（一行命令）：
#   curl -fsSL https://raw.githubusercontent.com/Azapp888/AzCode/260912-feat-android-native/tools/install.sh | bash
#
# 前置条件：
#   1. 手机安装并运行 magic App（无障碍/桥接服务开启，桥监听 127.0.0.1:8848）
#   2. 电脑已安装 adb 且已连接手机（adb devices 可见）
#   3. 电脑已安装 curl；建议安装 python3 以获得更好的 JSON 输出
set -euo pipefail

REPO="${LISTEN_REPO:-Azapp888/AzCode}"
BRANCH="${LISTEN_BRANCH:-260912-feat-android-native}"
PREFIX="${LISTEN_PREFIX:-$HOME/.local}"
BIN_DIR="$PREFIX/bin"
DEST="$BIN_DIR/listen"

command -v adb >/dev/null 2>&1 || echo "警告：未检测到 adb，安装后请先安装 adb 并连接手机。" >&2
command -v curl >/dev/null 2>&1 || { echo "错误：需要 curl。" >&2; exit 1; }

mkdir -p "$BIN_DIR"
echo "==> 下载 listen 到 $DEST"
curl -fsSL "https://raw.githubusercontent.com/$REPO/$BRANCH/tools/listen" -o "$DEST"
chmod +x "$DEST"

echo "==> 完成"
echo "  运行：listen run \"打开设置查看 Android 版本号\""
echo "  自检：listen health"
case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) echo "  提示：把 $BIN_DIR 加入 PATH，例如 export PATH=\"$BIN_DIR:\$PATH\"" ;;
esac
