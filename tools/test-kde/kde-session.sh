#!/bin/sh
# The user's KDE Wayland session: a session bus, PipeWire and WirePlumber,
# then KWin headless with two monitors side by side and a terminal. The
# portals start on demand through the bus. Runs as demo, so its processes
# carry WAYLAND_DISPLAY, XDG_CURRENT_DESKTOP and DBUS_SESSION_BUS_ADDRESS in
# their environment, as a real desktop session does.
export XDG_RUNTIME_DIR=/run/user/1000
export XDG_SESSION_TYPE=wayland
export XDG_CURRENT_DESKTOP=KDE
export KDE_FULL_SESSION=true
export WAYLAND_DISPLAY=wayland-0
export DBUS_SESSION_BUS_ADDRESS=unix:path=$XDG_RUNTIME_DIR/bus
export QT_QPA_PLATFORM=wayland
export LANG=C.UTF-8
# KWin captures the screen only when it composites with OpenGL, which its
# virtual backend does through a GPU node passed to the container (see
# README.md).
export KWIN_COMPOSE=O2
# Without systemd, KWin cannot match the portal backend to its .desktop file,
# which is what grants it the screencast and fake input protocols: drop the
# check (test container only).
export KWIN_WAYLAND_NO_PERMISSION_CHECKS=1
cd "$HOME" || exit 1

dbus-daemon --session --address="$DBUS_SESSION_BUS_ADDRESS" --nofork --nopidfile &
i=0
while [ ! -S "$XDG_RUNTIME_DIR/bus" ] && [ $i -lt 50 ]; do
  sleep 0.1
  i=$((i + 1))
done

pipewire &
sleep 0.5
wireplumber &

# KWin opens the Wayland socket; the portals it activates must see it.
dbus-update-activation-environment WAYLAND_DISPLAY XDG_CURRENT_DESKTOP XDG_SESSION_TYPE KDE_FULL_SESSION QT_QPA_PLATFORM XDG_RUNTIME_DIR
# KWin's own log goes to /tmp/kwin.log; the terminal starts once its socket is
# there.
kwin_wayland --virtual --width 1280 --height 800 --output-count 2 \
  --no-lockscreen --socket wayland-0 >/tmp/kwin.log 2>&1 &
i=0
while [ ! -S "$XDG_RUNTIME_DIR/wayland-0" ] && [ $i -lt 100 ]; do
  sleep 0.1
  i=$((i + 1))
done
foot -e bash -l &
wait
