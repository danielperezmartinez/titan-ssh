#!/bin/sh
# Starts the X11 desktop as the user demo, then sshd in the foreground.
su demo -s /bin/sh -c /usr/local/bin/x11-session.sh &
exec /usr/sbin/sshd -D -e
