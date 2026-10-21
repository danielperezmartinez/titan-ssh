#!/bin/sh
# The user's GNOME Wayland session: a session bus, PipeWire and WirePlumber,
# then GNOME Shell headless with two 1280x800 monitors and a terminal. The
# portals start on demand through the bus. Runs as demo, so its processes
# carry WAYLAND_DISPLAY, XDG_CURRENT_DESKTOP and DBUS_SESSION_BUS_ADDRESS in
# their environment, as a real desktop session does.
export XDG_RUNTIME_DIR=/run/user/1000
export XDG_SESSION_TYPE=wayland
export XDG_CURRENT_DESKTOP=GNOME
export WAYLAND_DISPLAY=wayland-0
export DBUS_SESSION_BUS_ADDRESS=unix:path=$XDG_RUNTIME_DIR/bus
export LANG=C.UTF-8
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

dbus-update-activation-environment WAYLAND_DISPLAY XDG_CURRENT_DESKTOP XDG_SESSION_TYPE XDG_RUNTIME_DIR
# GNOME Shell's log goes to /tmp/gnome-shell.log; the terminal starts once
# its socket is there.
(gnome-shell --headless --wayland --no-x11 --wayland-display=wayland-0 \
  --virtual-monitor 1280x800 --virtual-monitor 1280x800 \
  >/tmp/gnome-shell.log 2>&1; echo "gnome-shell exit $?" >>/tmp/gnome-shell.log) &
i=0
while [ ! -S "$XDG_RUNTIME_DIR/wayland-0" ] && [ $i -lt 200 ]; do
  sleep 0.1
  i=$((i + 1))
done
foot -e bash -l &
wait
