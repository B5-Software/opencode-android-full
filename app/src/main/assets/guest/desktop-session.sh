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
mkdir -p "$XDG_RUNTIME_DIR" "$XDG_CONFIG_HOME" "$XDG_CACHE_HOME" "$XDG_DATA_HOME" "$XDG_STATE_HOME" /desktop-state/web/vendor
rm -f /desktop-state/xfce-ready /desktop-state/stop-request /desktop-state/run/dbus-address
chmod 700 "$XDG_RUNTIME_DIR"
mkdir -p "$XDG_CONFIG_HOME/xfce4/xfconf/xfce-perchannel-xml"
if [ ! -f "$XDG_CONFIG_HOME/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml" ]; then
  cat > "$XDG_CONFIG_HOME/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml" <<'CONFIG'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0"><property name="general" type="empty"><property name="use_compositing" type="bool" value="false"/></property></channel>
CONFIG
fi
mkdir -p "$XDG_DATA_HOME/applications"
cat > "$XDG_DATA_HOME/applications/chromium.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=Chromium
Comment=Browser mode is selected in the Android desktop controls
Exec=/bin/bash /android-runtime/chromium-launch.sh %U
Icon=chromium
Terminal=false
Categories=Network;WebBrowser;
MimeType=text/html;x-scheme-handler/http;x-scheme-handler/https;
StartupNotify=true
DESKTOP
ln -sfn /usr/share/novnc /desktop-state/web/vendor/novnc
cp /android-runtime/desktop.html /desktop-state/web/index.html
vncpasswd -f < /desktop-state/private/vnc-input > /desktop-state/private/vnc.passwd
chmod 600 /desktop-state/private/vnc.passwd
rm /desktop-state/private/vnc-input
cleanup() { trap - EXIT; for pid in ${desktop_pid:-} ${gateway_pid:-} ${vnc_pid:-}; do kill "$pid" 2>/dev/null || true; done; wait || true; }
trap cleanup EXIT INT TERM
Xtigervnc :1 -localhost yes -rfbport 5901 -SecurityTypes VncAuth \
  -PasswordFile /desktop-state/private/vnc.passwd -geometry "${1:-1280x720}" \
  -depth 24 -nolisten tcp -desktop 'Debian Workspace' -AlwaysShared &
vnc_pid=$!
for i in $(seq 1 100); do
  kill -0 "$vnc_pid"
  if [ -S /tmp/.X11-unix/X1 ]; then break; fi
  sleep .1
done
python3 /android-runtime/desktop-gateway.py &
gateway_pid=$!
dbus-run-session -- /bin/bash -c 'umask 077; printf "%s\n" "$DBUS_SESSION_BUS_ADDRESS" > /desktop-state/run/dbus-address; exec xfce4-session' &
desktop_pid=$!
printf 'XFCE session starting on loopback display :1\n'
while kill -0 "$vnc_pid" 2>/dev/null && kill -0 "$gateway_pid" 2>/dev/null && kill -0 "$desktop_pid" 2>/dev/null; do
  if [ -f /desktop-state/stop-request ]; then exit 0; fi
  if [ ! -f /desktop-state/xfce-ready ] && xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q 'window id'; then
    touch /desktop-state/xfce-ready
    printf 'XFCE window manager ready\n'
    /bin/bash /android-runtime/desktop-open-project.sh "${2:-/workspace}" &
  fi
  sleep .5
done
exit 1
