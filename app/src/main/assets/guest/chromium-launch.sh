#!/bin/bash
set -euo pipefail
options=(--disable-dev-shm-usage --disable-background-networking --disable-component-update --disable-sync --no-first-run)
if [ -f /desktop-state/chromium-compat-enabled ]; then
  options+=(--no-sandbox --user-data-dir=/desktop-state/chromium-compat)
else
  options+=(--user-data-dir=/desktop-state/chromium-standard)
fi
exec /usr/bin/chromium "${options[@]}" "$@"
