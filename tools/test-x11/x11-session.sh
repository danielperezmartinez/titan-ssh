#!/bin/sh
# The user's X11 session: Xvfb on :1 with a fresh cookie, a window manager,
# a solid background, a clock that changes every second and a terminal.
# Runs as demo, so its processes carry DISPLAY and XAUTHORITY in their
# environment, as a real desktop session does.
export DISPLAY=:1
export XAUTHORITY=/home/demo/.Xauthority
rm -f "$XAUTHORITY"
xauth -q -f "$XAUTHORITY" add :1 MIT-MAGIC-COOKIE-1 "$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"

Xvfb :1 -screen 0 1280x800x24 -auth "$XAUTHORITY" -nolisten tcp &
i=0
while [ ! -S /tmp/.X11-unix/X1 ] && [ $i -lt 50 ]; do
  sleep 0.1
  i=$((i + 1))
done

twm &
xsetroot -solid '#3a6ea5'
xclock -update 1 -geometry 220x220+40+40 &
xterm -geometry 80x24+320+60 -e bash -l &
wait
