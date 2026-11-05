#!/bin/bash
set -euo pipefail
CAST_ROOT="$(cd -- "$(dirname -- "$0")" && pwd)"
exec "$CAST_ROOT/启动鸿蒙投屏.command" --diagnose
