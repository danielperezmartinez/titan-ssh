#!/bin/sh
# The user's sway session on the headless backend: two virtual outputs and
# the pixman renderer, no input devices of its own (the agent brings a
# virtual pointer and keyboard). Its processes carry WAYLAND_DISPLAY,
# XDG_RUNTIME_DIR and XDG_CURRENT_DESKTOP, as a real session does. Restarts
# sway if it ends, so a test can `swaymsg exit` to start over.
export XDG_RUNTIME_DIR=/run/user/1000
export XDG_SESSION_TYPE=wayland
export XDG_CURRENT_DESKTOP=sway
export WLR_BACKENDS=headless
export WLR_HEADLESS_OUTPUTS=2
export WLR_RENDERER=pixman
export WLR_LIBINPUT_NO_DEVICES=1
cd /home/demo
while true; do
  sway -d >/tmp/sway.log 2>&1
  sleep 1
done
