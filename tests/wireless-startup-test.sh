#!/bin/bash
set -euo pipefail
CAST_SOURCE="$(cd -- "$(dirname -- "$0")/.." && pwd)"
CAST_FIXTURE=$(mktemp -d)
export CAST_FIXTURE
trap 'rm -r -- "$CAST_FIXTURE"' EXIT
CAST_TEST_PROJECT="$CAST_FIXTURE/project with spaces"
mkdir -p "$CAST_TEST_PROJECT/scripts" "$CAST_TEST_PROJECT/build" "$CAST_FIXTURE/bin" "$CAST_FIXTURE/state"
cp "$CAST_SOURCE/启动鸿蒙投屏.command" "$CAST_TEST_PROJECT/"
cp "$CAST_SOURCE/scripts/environment.sh" "$CAST_TEST_PROJECT/scripts/"
touch "$CAST_TEST_PROJECT/build/AutoStart.class" "$CAST_TEST_PROJECT/build/HdcCommand.class" "$CAST_FIXTURE/bin/hdc"
chmod +x "$CAST_FIXTURE/bin/hdc"
cat > "$CAST_FIXTURE/bin/java" <<'MOCK'
#!/bin/bash
if [[ " $* " == *' HdcCommand '* ]]; then
    while [[ "$1" != HdcCommand ]]; do shift; done
    shift 3
    printf '%s\n' "$*" >> "$CAST_FIXTURE/commands"
    case "$1" in
        list)
            echo 'USB-TEST USB Connected localhost'
            if [[ -f "$CAST_FIXTURE/connected" ]]; then printf '%s TCP Connected localhost\n' "$(cat "$CAST_FIXTURE/connected")"; fi
            if [[ -f "$CAST_FIXTURE/offline" ]]; then printf '%s TCP Offline localhost\n' "$(cat "$CAST_FIXTURE/offline")"; fi
            ;;
        tconn)
            if [[ "${3:-}" == '-remove' ]]; then
                rm -f "$CAST_FIXTURE/offline"
            elif [[ ! -f "$CAST_FIXTURE/unavailable" && ! -f "$CAST_FIXTURE/offline" ]]; then
                printf '%s\n' "$2" > "$CAST_FIXTURE/connected"
            fi
            ;;
    esac
else
    printf '%s\n' "$@" > "$CAST_FIXTURE/viewer-args"
    printf '%s\n' "${CAST_STATE_DIR:-unset}" > "$CAST_FIXTURE/viewer-state"
fi
MOCK
cat > "$CAST_FIXTURE/bin/route" <<'MOCK'
#!/bin/bash
echo 'interface: test0'
MOCK
cat > "$CAST_FIXTURE/bin/ipconfig" <<'MOCK'
#!/bin/bash
echo '198.51.100.10'
MOCK
chmod +x "$CAST_FIXTURE/bin/java" "$CAST_FIXTURE/bin/route" "$CAST_FIXTURE/bin/ipconfig"
export CAST_JAVA_PATH="$CAST_FIXTURE/bin/java" CAST_HDC_PATH="$CAST_FIXTURE/bin/hdc" CAST_STATE_DIR="$CAST_FIXTURE/state" CAST_GUI=1
launch() { bash "$CAST_TEST_PROJECT/启动鸿蒙投屏.command" "$@" >/dev/null; }
check_preferred() {
    if ! rg -q -- "^-Dcast.device=$1$" "$CAST_FIXTURE/viewer-args"; then
        echo "FAIL: startup did not prefer saved Wi-Fi target $1" >&2; exit 1
    fi
}
printf '192.0.2.25:8710\n' > "$CAST_STATE_DIR/wireless-device.txt"
printf '192.0.2.25:8710\n' > "$CAST_FIXTURE/offline"
launch
check_preferred '192.0.2.25:8710'
rg -q -- '^tconn 192.0.2.25:8710 -remove$' "$CAST_FIXTURE/commands" || { echo 'FAIL: stale offline Wi-Fi not removed'; exit 1; }
[[ "$(cat "$CAST_FIXTURE/viewer-state")" == "$CAST_STATE_DIR" ]] || { echo 'FAIL: viewer state directory not shared'; exit 1; }
printf '192.0.2.26:8710\n' > "$CAST_STATE_DIR/wireless-device.txt"
launch --auto
check_preferred '192.0.2.26:8710'
launch --usb
if rg -q -- '^-Dcast.device=' "$CAST_FIXTURE/viewer-args"; then echo 'FAIL: explicit USB ignored'; exit 1; fi
rm "$CAST_FIXTURE/connected"
touch "$CAST_FIXTURE/unavailable"
launch
if rg -q -- '^-Dcast.device=' "$CAST_FIXTURE/viewer-args"; then echo 'FAIL: unavailable Wi-Fi selected'; exit 1; fi
[[ "$(cat "$CAST_STATE_DIR/wireless-device.txt")" == '192.0.2.26:8710' ]] || { echo 'FAIL: unavailable Wi-Fi memory lost'; exit 1; }
echo 'PASS: default Wi-Fi priority, latest remembered phone, shared state, explicit USB, offline fallback preserves memory'
