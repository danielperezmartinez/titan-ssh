# Throwaway SSH destination with a GNOME Wayland desktop, for testing the
# remote desktop through the RemoteDesktop and ScreenCast portals, Mutter's
# own remote desktop API and PipeWire (ADR-0031). It runs GNOME Shell
# headless with two virtual monitors (software rendering, no GPU needed), the
# GNOME portal backend, PipeWire and WirePlumber, all as the user `demo`,
# with no logind: the agent finds the session through /proc.
#
# Test-only credentials: user `demo`, password `demo`. Publish the port on
# loopback only (see README.md); never expose it on the network.
FROM debian:trixie-slim

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        openssh-server dbus dbus-daemon dbus-bin \
        gnome-shell mutter-common xdg-desktop-portal xdg-desktop-portal-gnome \
        gsettings-desktop-schemas adwaita-icon-theme \
        pipewire pipewire-bin wireplumber \
        foot fonts-dejavu-core procps psmisc python3 \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p /run/sshd \
 && useradd -m -u 1000 -s /bin/bash demo \
 && echo 'demo:demo' | chpasswd \
 && mkdir -p /home/demo/proyecto /home/demo/.ssh \
 && echo 'hola desde el escritorio GNOME Wayland de pruebas' > /home/demo/proyecto/LEEME \
 && chmod 700 /home/demo/.ssh \
 && chown -R demo:demo /home/demo \
 && sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config \
 && sed -i 's/^#\?AllowTcpForwarding.*/AllowTcpForwarding yes/' /etc/ssh/sshd_config

COPY gnome-session.sh /usr/local/bin/gnome-session.sh
COPY start.sh /usr/local/bin/start.sh
RUN chmod 755 /usr/local/bin/gnome-session.sh /usr/local/bin/start.sh

EXPOSE 22
CMD ["/usr/local/bin/start.sh"]
