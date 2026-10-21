#!/bin/sh
# Starts the GNOME Wayland desktop as the user demo, then sshd in the
# foreground.
mkdir -p /run/user/1000
chown demo:demo /run/user/1000
chmod 700 /run/user/1000
su demo -s /bin/sh -c /usr/local/bin/gnome-session.sh &
exec /usr/sbin/sshd -D -e
