#!/bin/sh
# Starts the servers of the RDP test destination (see Dockerfile).
set -e

/usr/sbin/sshd -e

# xrdp (TLS, graphical login) on 127.0.0.1:3389 inside the container.
/usr/sbin/xrdp-sesman
/usr/sbin/xrdp

# A virtual display with a terminal, mirrored with NLA by FreeRDP's shadow server.
su demo -c 'Xvfb :99 -screen 0 1280x800x24 -ac -nolisten tcp >/tmp/xvfb.log 2>&1 &'
sleep 1
su demo -c 'DISPLAY=:99 openbox >/tmp/openbox.log 2>&1 &'
su demo -c 'DISPLAY=:99 xterm -geometry 100x30+40+40 -title nla-shadow >/dev/null 2>&1 &'

SHADOW=$(command -v freerdp-shadow-cli3 || command -v freerdp-shadow-cli)
export DISPLAY=:99
exec "$SHADOW" /bind-address:127.0.0.1 /port:3390 /sec:nla /sam-file:/etc/titan-rdp/SAM
