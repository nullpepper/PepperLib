#!/usr/bin/env bash
# 真实 Paper 服务器启动 smoke（双模式重构文档 §9.3 / §9.4；自适应加载 §4.1）。
#
#   thin   : PepperLib.jar + PepperClaim.jar + PepperUnion.jar 三件套（前置模式，最新 Paper）
#   shaded : 仅 ShadedExample.jar（shade 模式，无前置插件，最新 Paper）
#   legacy : 仅 PepperLib.jar（前置插件，Paper 1.18.2 最低支持版本；验证自适应加载）
#
# 用法：bash scripts/paper-smoke.sh <thin|shaded|legacy> [workdir]
set -euo pipefail

MODE="${1:-thin}"
WORK="${2:-$(mktemp -d)}"

case "$MODE" in
  thin|shaded)
    PAPER_VERSION="26.1.2"
    PAPER_BUILD="74"
    ;;
  legacy)
    # 最低支持版本（api-version '1.18' 的 plugin.yml 在此版本首验）。
    PAPER_VERSION="1.18.2"
    PAPER_BUILD="388"
    ;;
  *)
    echo "unknown mode: $MODE (thin|shaded|legacy)" >&2
    exit 2
    ;;
esac

echo "::group::paper-smoke ($MODE)"
echo "workdir: $WORK"

echo "-- resolve Paper $PAPER_VERSION-$PAPER_BUILD download url"
PAPER_URL="$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/${PAPER_VERSION}/builds/${PAPER_BUILD}" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['downloads']['server:default']['url'])")"
echo "url: $PAPER_URL"

mkdir -p "$WORK/plugins"
if [ ! -f "$WORK/paper.jar" ]; then
  echo "-- download paper server jar"
  curl -fsSL -o "$WORK/paper.jar" "$PAPER_URL"
fi
echo "eula=true" > "$WORK/eula.txt"
# 独立端口 + 离线模式：避免与本地其他服务器冲突、加速启动（smoke 只验插件加载）。
cat > "$WORK/server.properties" <<EOF
server-port=${PAPER_SMOKE_PORT:-25599}
online-mode=false
motd=PepperLib paper smoke
EOF
# 防御：复用 workdir 时清理上次运行残留（半初始化 world 会导致启动崩溃）。
rm -rf "$WORK/world" "$WORK/world_nether" "$WORK/world_the_end" "$WORK/plugins"/*.jar 2>/dev/null || true
mkdir -p "$WORK/plugins"

# 复制最新的构建产物（build/libs 可能残留旧版本 jar——Gradle 不清理，
# glob 全复制会导致服务器加载旧版本；取最新一个）。
latest_jar() {
  ls -t "$1"/build/libs/"$2"-*.jar 2>/dev/null | head -1
}

case "$MODE" in
  thin)
    cp "$(latest_jar pepper-lib-plugin PepperLib)" "$WORK/plugins/"
    cp consumers/PepperClaim/build/libs/PepperClaim-*.jar "$WORK/plugins/"
    cp consumers/PepperUnion/build/libs/PepperUnion.jar "$WORK/plugins/"
    ;;
  shaded)
    cp "$(latest_jar pepper-lib-shaded-example ShadedExample)" "$WORK/plugins/"
    ;;
  legacy)
    cp "$(latest_jar pepper-lib-plugin PepperLib)" "$WORK/plugins/"
    ;;
  *)
    echo "unknown mode: $MODE" >&2
    exit 2
    ;;
esac
echo "-- plugins:"
ls -la "$WORK/plugins/"

echo "-- start server"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$JAVA_BIN" -version 2>&1 | head -1
(cd "$WORK" && timeout 480 "$JAVA_BIN" -jar paper.jar --nogui > server.log 2>&1) &
SERVER_PID=$!

echo "-- wait for startup (max 240s)"
STARTED=0
for _ in $(seq 1 120); do
  if grep -q "Done (" "$WORK/server.log" 2>/dev/null; then
    STARTED=1
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "!! server process exited early"
    break
  fi
  sleep 2
done

if [ "$STARTED" != "1" ]; then
  echo "!! server did not finish starting; tail of log:"
  tail -40 "$WORK/server.log" || true
  exit 1
fi
echo "-- server started"

case "$MODE" in
  thin)
    grep -q "Enabling PepperLib" "$WORK/server.log" \
      || { echo "!! PepperLib was not enabled"; tail -40 "$WORK/server.log"; exit 1; }
    grep -q "已启用（共享库前置" "$WORK/server.log" \
      || { echo "!! PepperLib onEnable did not run"; tail -40 "$WORK/server.log"; exit 1; }
    grep -q "Enabling PepperClaim" "$WORK/server.log" \
      || { echo "!! PepperClaim was not enabled"; tail -40 "$WORK/server.log"; exit 1; }
    grep -q "Enabling PepperUnion" "$WORK/server.log" \
      || { echo "!! PepperUnion was not enabled"; tail -40 "$WORK/server.log"; exit 1; }
    # 最新 Paper（≥1.21）：gui-host 必须可用，不得出现禁用 warning。
    if grep -q "gui-host 特性已禁用" "$WORK/server.log"; then
      echo "!! gui-host must be available on latest Paper"; exit 1
    fi
    ;;
  shaded)
    grep -q "Enabling ShadedExample" "$WORK/server.log" \
      || { echo "!! ShadedExample was not enabled"; tail -40 "$WORK/server.log"; exit 1; }
    grep -q "ShadedExample: shaded-ok" "$WORK/server.log" \
      || { echo "!! relocated PepperLib usage did not work"; tail -40 "$WORK/server.log"; exit 1; }
    ;;
  legacy)
    grep -q "Enabling PepperLib" "$WORK/server.log" \
      || { echo "!! PepperLib was not enabled on legacy Paper"; tail -40 "$WORK/server.log"; exit 1; }
    # 低版本（<1.21）：gui-host 必须被检测并禁用（自适应加载核心断言）。
    grep -q "gui-host 特性已禁用" "$WORK/server.log" \
      || { echo "!! gui-host was not disabled on legacy Paper"; tail -40 "$WORK/server.log"; exit 1; }
    ;;
esac

if grep -E "Error occurred (enabling|loading)|Failed to load plugin|Plugin .* threw an exception" "$WORK/server.log"; then
  echo "!! fatal plugin error found in log"
  exit 1
fi

kill "$SERVER_PID" 2>/dev/null || true
# 兜底：kill 外层 shell 可能不终止 java（timeout 包装）；pkill 清掉所有 smoke 服务器。
pkill -f "paper.jar --nogui" 2>/dev/null || true
sleep 1
echo "PASS: paper-smoke ($MODE)"
echo "::endgroup::"
