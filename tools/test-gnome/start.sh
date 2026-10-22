#!/bin/sh
# Starts the GNOME Wayland desktop as the user demo, then sshd in the
# foreground.
# Packages leave an empty /run/systemd/seats behind, which makes GNOME Shell
# look for logind on a system bus there is none of.
rm -rf /run/systemd
# GNOME Shell needs a system bus, even one with nothing on it.
mkdir -p /run/dbus
rm -f /run/dbus/pid
dbus-daemon --system --fork
mkdir -p /run/user/1000
chown demo:demo /run/user/1000
chmod 700 /run/user/1000
su demo -s /bin/sh -c /usr/local/bin/gnome-session.sh &
exec /usr/sbin/sshd -D -e
