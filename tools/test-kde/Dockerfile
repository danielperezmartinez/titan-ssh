# Throwaway SSH destination with a KDE Plasma Wayland desktop, for testing the
# remote desktop through the RemoteDesktop and ScreenCast portals and
# PipeWire (ADR-0031). It runs KWin headless (`kwin_wayland --virtual`) with
# two virtual monitors, the KDE portal backend, PipeWire and WirePlumber, all
# as the user `demo`, with no logind: the agent finds the session through
# /proc, as on a destination without systemd.
#
# Test-only credentials: user `demo`, password `demo`. Publish the port on
# loopback only (see README.md); never expose it on the network.
FROM debian:trixie-slim

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        openssh-server dbus dbus-daemon dbus-bin \
        kwin-wayland xdg-desktop-portal xdg-desktop-portal-kde \
        pipewire pipewire-bin wireplumber \
        foot kdialog qml6-module-qtquick-controls xwayland qt6-wayland \
        fonts-dejavu-core procps psmisc python3 \
 && rm -rf /var/lib/apt/lists/* \
 # kwin_wayland carries file capabilities (CAP_SYS_NICE) that a container
 # refuses to exec: a plain copy drops them.
 && cp /usr/bin/kwin_wayland /tmp/kwin_wayland && mv /tmp/kwin_wayland /usr/bin/kwin_wayland \
 && mkdir -p /run/sshd \
 && useradd -m -u 1000 -s /bin/bash demo \
 && echo 'demo:demo' | chpasswd \
 && mkdir -p /home/demo/proyecto /home/demo/.ssh \
 && echo 'hola desde el escritorio KDE Wayland de pruebas' > /home/demo/proyecto/LEEME \
 && chmod 700 /home/demo/.ssh \
 && chown -R demo:demo /home/demo \
 && sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config \
 && sed -i 's/^#\?AllowTcpForwarding.*/AllowTcpForwarding yes/' /etc/ssh/sshd_config

COPY kde-session.sh /usr/local/bin/kde-session.sh
COPY start.sh /usr/local/bin/start.sh
RUN chmod 755 /usr/local/bin/kde-session.sh /usr/local/bin/start.sh

EXPOSE 22
CMD ["/usr/local/bin/start.sh"]
