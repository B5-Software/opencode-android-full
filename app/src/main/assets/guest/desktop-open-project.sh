#!/bin/bash
set -euo pipefail
export DISPLAY=:1
export XDG_RUNTIME_DIR=/desktop-state/run
export XDG_CONFIG_HOME=/desktop-state/config
export XDG_CACHE_HOME=/desktop-state/cache
export XDG_DATA_HOME=/desktop-state/data
export XDG_STATE_HOME=/desktop-state/state
export NO_AT_BRIDGE=1
export LIBGL_ALWAYS_SOFTWARE=1
IFS= read -r DBUS_SESSION_BUS_ADDRESS < /desktop-state/run/dbus-address
export DBUS_SESSION_BUS_ADDRESS
cd -P -- "${1:?missing project directory}"
exec /usr/bin/thunar -- "$PWD"
