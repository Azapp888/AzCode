#!/usr/bin/env bash
# Listen 安装脚本：把 magic / Listen 安装到 ~/.local/bin。
#
# 用法（一行命令）：
#   curl -fsSL https://raw.githubusercontent.com/Azapp888/AzCode/260912-feat-linux-native/linux/install.sh | bash
#
# 自定义安装目录：LISTEN_PREFIX=/usr/local ./install.sh
set -euo pipefail

REPO="${LISTEN_REPO:-Azapp888/AzCode}"
BRANCH="${LISTEN_BRANCH:-260912-feat-linux-native}"
PREFIX="${LISTEN_PREFIX:-$HOME/.local}"
LIB_DIR="$PREFIX/lib/magic-listen"
BIN_DIR="$PREFIX/bin"

echo "==> 安装 magic / Listen 到 $PREFIX"
mkdir -p "$LIB_DIR" "$BIN_DIR"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "==> 下载源码（$REPO@$BRANCH）"
curl -fsSL "https://github.com/$REPO/archive/refs/heads/$BRANCH.tar.gz" -o "$TMP/src.tar.gz"
tar -xzf "$TMP/src.tar.gz" -C "$TMP"
SRC="$(find "$TMP" -maxdepth 1 -type d -name 'AzCode-*' | head -n1)"
if [ -z "$SRC" ] || [ ! -d "$SRC/linux/listen_agent" ]; then
  echo "错误：下载的压缩包中未找到 linux/listen_agent" >&2
  exit 1
fi

cp -R "$SRC/linux/listen_agent" "$LIB_DIR/"
cp "$SRC/linux/magic_listen.py" "$LIB_DIR/"

cat > "$BIN_DIR/listen" <<EOF
#!/usr/bin/env bash
exec python3 "$LIB_DIR/magic_listen.py" --cli "\$@"
EOF

cat > "$BIN_DIR/magic" <<EOF
#!/usr/bin/env bash
exec python3 "$LIB_DIR/magic_listen.py" --gui "\$@"
EOF

chmod +x "$BIN_DIR/listen" "$BIN_DIR/magic"

echo "==> 完成"
echo "  命令行：$BIN_DIR/listen   （例如：listen chat）"
echo "  图形界面：$BIN_DIR/magic"
case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) echo "  提示：把 $BIN_DIR 加入 PATH，例如 export PATH=\"$BIN_DIR:\$PATH\"" ;;
esac
