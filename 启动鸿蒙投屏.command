#!/bin/bash
set -euo pipefail
CAST_ROOT="$(cd -- "$(dirname -- "$0")" && pwd)"
source "$CAST_ROOT/scripts/environment.sh"
CAST_JDK="$(cast_jdk "${CAST_RUNTIME_JDK_PATH:-}")"
CAST_TOOLS="$(cast_toolchains "${CAST_TOOLCHAINS_PATH:-}")"
CAST_HDC="$(cast_absolute_path "${CAST_HDC_PATH:-$CAST_TOOLS/hdc}")"
CAST_JAVA="$(cast_absolute_path "${CAST_JAVA_PATH:-$CAST_JDK/bin/java}")"
CAST_STATE="$(cast_absolute_path "${CAST_STATE_DIR:-$CAST_ROOT}")"
export CAST_STATE_DIR="$CAST_STATE"
export CAST_HDC_PATH="$CAST_HDC"
export CAST_PROJECT_DIR="$CAST_ROOT"
if [[ -n "${CAST_OCR_PATH:-}" ]]; then
    export CAST_OCR_PATH="$(cast_absolute_path "$CAST_OCR_PATH")"
fi
if [[ -n "${CAST_TESSERACT_PATH:-}" ]]; then
    export CAST_TESSERACT_PATH="$(cast_absolute_path "$CAST_TESSERACT_PATH")"
fi
if [[ -n "${CAST_TESSDATA_PATH:-}" ]]; then
    export CAST_TESSDATA_PATH="$(cast_absolute_path "$CAST_TESSDATA_PATH")"
fi
mkdir -p "$CAST_STATE"
export PATH="$(dirname -- "$CAST_HDC"):$(dirname -- "$CAST_JAVA"):$PATH"
export JAVA_HOME="$(dirname -- "$(dirname -- "$CAST_JAVA")")"
cd "$CAST_STATE"

CAST_MODE="${1:---auto}"
CAST_WIRELESS_TARGET=""
CAST_MAC_IP=""
CAST_MAC_SUBNET=""
CAST_USB_TARGETS=""

cast_fail() {
    echo "$1"
    if [[ "${CAST_GUI:-0}" != "1" ]]; then
        read -r -p "按回车关闭" _cast_answer
    fi
    exit 1
}
if [[ ! -x "$CAST_HDC" || ! -x "$CAST_JAVA" ]]; then
    cast_fail "找不到鸿蒙调试工具或 Java，请按 README 准备依赖。
Java：$CAST_JAVA
hdc：$CAST_HDC"
fi
if [[ ! -f "$CAST_ROOT/build/AutoStart.class" || ! -f "$CAST_ROOT/build/HdcCommand.class" ]]; then
    cast_fail "尚未编译项目，请先运行项目目录下的 ./build.sh。"
fi

CAST_JAVA_OPTIONS=(-Xdock:name="鸿蒙投屏")
if [[ -n "${CAST_APP_ICON:-}" ]]; then
    CAST_JAVA_OPTIONS+=("-Xdock:icon=$CAST_APP_ICON")
fi

cast_hdc() {
    "$CAST_JAVA" -cp "$CAST_ROOT/build" HdcCommand 8 "$CAST_HDC" "$@"
}
cast_connected() {
    cast_hdc list targets -v | awk -v target="$1" '$1 == target && $3 == "Connected" { found = 1 } END { exit !found }'
}
cast_reconnect_wifi() {
    local target="$1"
    if cast_hdc list targets -v | awk -v target="$target" '$1 == target { found = 1 } END { exit !found }'; then
        cast_hdc tconn "$target" -remove || true
    fi
    cast_hdc tconn "$target" || true
}
cast_wifi_usable() {
    cast_hdc -t "$1" shell echo HARMONY_CAST_ALIVE | awk '
        $1 == "HARMONY_CAST_ALIVE" && NF == 1 { found = 1 } END { exit !found }'
}
cast_try_wifi() {
    cast_wifi_usable "$1" && return 0
    cast_reconnect_wifi "$1"
    cast_wifi_usable "$1"
}
cast_target_valid() {
    [[ "$1" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}:8710$ ]]
}
cast_ip_valid() {
    [[ "$1" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]
}
cast_subnet() {
    local ip="$1"
    if [[ "$ip" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)\.[0-9]+$ ]]; then
        printf '%s.%s.%s' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}"
    fi
}
cast_mac_ip() {
    local iface
    iface=$(route -n get default 2>/dev/null | awk '/interface:/{print $2; exit}')
    [[ -z "$iface" ]] && iface="en0"
    ipconfig getifaddr "$iface" 2>/dev/null || true
}
cast_mac_ssid() {
    system_profiler SPAirPortDataType 2>/dev/null | awk '
        /Current Network Information:/ { getline; sub(/^[[:space:]]*[0-9]+\.?[[:space:]]*/, ""); sub(/:.*/, ""); print; exit }' || true
}
cast_first_ipv4() {
    grep -oE 'inet[[:space:]]+(addr:)?[0-9]{1,3}(\.[0-9]{1,3}){3}' | {
        found=""
        while read -r _type ip; do
            ip="${ip#addr:}"
            if cast_ip_valid "$ip" && [[ "$ip" != 127.* && "$ip" != 169.254.* && "$ip" != "0.0.0.0" ]]; then
                found="$ip"
                break
            fi
        done
        printf '%s' "$found"
    }
}
cast_phone_ip() {
    local serial="$1"
    local ip
    ip=$(cast_hdc -t "$serial" shell ifconfig wlan0 2>/dev/null | cast_first_ipv4 || true)
    if [[ -z "$ip" ]]; then
        ip=$(cast_hdc -t "$serial" shell ip -4 addr show dev wlan0 2>/dev/null | cast_first_ipv4 || true)
    fi
    printf '%s' "$ip"
}
cast_ping_ok() {
    ping -c 1 -t 2 "$1" >/dev/null 2>&1
}

if [[ -f "$CAST_STATE/wireless-device.txt" ]]; then
    read -r CAST_WIRELESS_TARGET < "$CAST_STATE/wireless-device.txt" || true
fi
CAST_MAC_IP=$(cast_mac_ip)
CAST_MAC_SUBNET=$(cast_subnet "$CAST_MAC_IP")

cast_diagnose() {
    echo "=== 无线投屏诊断 ==="
    echo "Mac 网络："
    local iface
    iface=$(route -n get default 2>/dev/null | awk '/interface:/{print $2; exit}')
    echo "  默认路由接口：${iface:-未知}"
    echo "  Mac IP：${CAST_MAC_IP:-未获取}"
    local ssid
    ssid=$(cast_mac_ssid)
    [[ -n "$ssid" ]] && echo "  WiFi 名称：$ssid"
    echo "  默认网关：$(route -n get default 2>/dev/null | awk '/gateway:/{print $2; exit}')"
    echo ""
    echo "记忆的无线目标：${CAST_WIRELESS_TARGET:-无}"
    if [[ -n "$CAST_WIRELESS_TARGET" ]]; then
        local tip="${CAST_WIRELESS_TARGET%:8710}"
        local tsub
        tsub=$(cast_subnet "$tip")
        if [[ -n "$tsub" && -n "$CAST_MAC_SUBNET" ]]; then
            if [[ "$tsub" == "$CAST_MAC_SUBNET" ]]; then
                echo "  目标网段 $tsub.x 与 Mac 相同 ✓"
                if cast_ping_ok "$tip"; then
                    echo "  可达性：ping 通 ✓（可直接尝试投屏）"
                else
                    echo "  可达性：ping 不通 — 可能路由器开启「AP 隔离 / 访客网络」、防火墙拦截，或手机已休眠"
                fi
            else
                echo "  目标网段 $tsub.x 与 Mac 网段 $CAST_MAC_SUBNET.x 不同 ✗ — 手机当前可能连接了别的 WiFi/网络，需重新读取地址"
            fi
        fi
        if cast_connected "$CAST_WIRELESS_TARGET"; then
            echo "  hdc 状态：已连接 ✓"
        else
            echo "  hdc 状态：未连接"
        fi
    fi
    echo ""
    echo "hdc 设备列表："
    cast_hdc list targets -v || true
    CAST_USB_TARGETS=$(cast_hdc list targets -v | awk '$2 == "USB" && $3 == "Connected" { print $1 }')
    if [[ -n "$CAST_USB_TARGETS" ]]; then
        local serial
        serial=$(printf '%s\n' "$CAST_USB_TARGETS" | head -1)
        local pip
        pip=$(cast_phone_ip "$serial")
        echo "USB 设备：$serial"
        echo "  手机 WLAN 地址：${pip:-未读取到（请确认手机已开启 WiFi 并连接网络）}"
        if [[ -n "$pip" && -n "$CAST_MAC_SUBNET" ]]; then
            if [[ "$(cast_subnet "$pip")" == "$CAST_MAC_SUBNET" ]]; then
                echo "  网段：与 Mac 相同 ✓"
            else
                echo "  网段：与 Mac 网段 $CAST_MAC_SUBNET.x 不同 ✗"
            fi
        fi
    else
        echo "USB 设备：无（如需重新读取手机 WiFi 地址，请插线并在手机上允许 USB 调试）"
    fi
    echo ""
    echo "建议：手机连接与 Mac 相同的 WiFi（网段 ${CAST_MAC_SUBNET:-未知}.x），USB 插线授权后重新运行无线投屏入口。"
}

if [[ "$CAST_MODE" == "--diagnose" ]]; then
    cast_diagnose
    exit 0
fi

CAST_SELECTED_WIFI=""
if [[ "$CAST_MODE" == "--wireless" || "$CAST_MODE" == "--auto" ]]; then
    # Address prefixes alone do not determine reachability (e.g. a /21 LAN).
    if cast_target_valid "$CAST_WIRELESS_TARGET" && cast_try_wifi "$CAST_WIRELESS_TARGET"; then
        CAST_SELECTED_WIFI="$CAST_WIRELESS_TARGET"
    else
        CAST_USB_TARGETS=$(cast_hdc list targets -v | awk '$2 == "USB" && $3 == "Connected" { print $1 }')
        if [[ -n "$CAST_USB_TARGETS" && "$CAST_USB_TARGETS" != *$'\n'* ]]; then
            CAST_PHONE_IP=$(cast_phone_ip "$CAST_USB_TARGETS")
            if cast_ip_valid "$CAST_PHONE_IP"; then
                CAST_PHONE_WIFI="$CAST_PHONE_IP:8710"
                if cast_wifi_usable "$CAST_PHONE_WIFI"; then
                    CAST_SELECTED_WIFI="$CAST_PHONE_WIFI"
                else
                    echo "正在配置 Wi-Fi 投屏，请保持手机和 Mac 在同一局域网。"
                    cast_hdc -t "$CAST_USB_TARGETS" tmode port 8710 || true
                    cast_reconnect_wifi "$CAST_PHONE_WIFI"
                    if cast_wifi_usable "$CAST_PHONE_WIFI"; then CAST_SELECTED_WIFI="$CAST_PHONE_WIFI"; fi
                fi
            else
                echo "未能读取手机 WLAN 地址：请确认手机已开启并连接 WiFi（USB 已连接）。"
            fi
        fi
        if [[ -z "$CAST_SELECTED_WIFI" ]]; then
            CAST_TCP_TARGET=$(cast_hdc list targets -v | awk '$2 == "TCP" && $3 == "Connected" { print $1 }')
            if cast_target_valid "$CAST_TCP_TARGET" && cast_wifi_usable "$CAST_TCP_TARGET"; then
                CAST_SELECTED_WIFI="$CAST_TCP_TARGET"
            fi
        fi
    fi
    if [[ "$CAST_MODE" == "--wireless" && -z "$CAST_SELECTED_WIFI" ]]; then
        cast_fail "无线连接未成功。

请按顺序检查：
1) 手机连接与 Mac 相同的 WiFi（Mac 当前网段：${CAST_MAC_SUBNET:-未知}）
2) 用 USB 数据线连接手机，并在手机上允许「USB 调试」
3) 重新运行本入口（会自动读取手机当前 WiFi 地址）
4) 仍失败时：路由器若开启「AP 隔离 / 访客网络」会阻止设备互相访问；也可在 macOS 系统设置中临时关闭防火墙后重试。"
    fi
    if [[ -n "$CAST_SELECTED_WIFI" ]]; then
        CAST_JAVA_OPTIONS+=("-Dcast.device=$CAST_SELECTED_WIFI")
        echo "Wi-Fi 已连接，可以拔掉数据线。"
        CAST_USB_TARGETS=""
    fi
elif [[ "$CAST_MODE" == "--usb" ]]; then
    CAST_USB_TARGETS=$(cast_hdc list targets -v | awk '$2 == "USB" && $3 == "Connected" { print $1 }')
fi
# Remembered Wi-Fi entries are selectable even when offline, so the viewer
# needs the USB serial to auto-connect when it is the only connected USB device.
if [[ -n "$CAST_USB_TARGETS" && "$CAST_USB_TARGETS" != *$'\n'* ]]; then
    CAST_JAVA_OPTIONS+=("-Dcast.device=$CAST_USB_TARGETS")
fi
CAST_JAVA_OPTIONS+=("-Dcast.connectionMode=$CAST_MODE")
exec "$CAST_JAVA" "${CAST_JAVA_OPTIONS[@]}" -cp "$CAST_ROOT/build:$CAST_ROOT/vendor/hosScrcpy-1.0.18-beta.jar:$CAST_ROOT/vendor/release/HOScrcpy/libs/*" AutoStart
