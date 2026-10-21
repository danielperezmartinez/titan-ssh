# Throwaway SSH destination with an X11 desktop, for testing the remote
# desktop and mouse pad sessions against Linux X11 (ADR-0029). It is the
# test-sshd image plus a virtual X server (Xvfb) on display :1, owned by the
# user `demo` and protected by a MIT-MAGIC-COOKIE-1 in ~/.Xauthority, with a
# window manager, a clock that changes every second and a terminal.
#
# Test-only credentials: user `demo`, password `demo`. Publish the port on
# loopback only (see README.md); never expose it on the network.
FROM alpine:3.20

RUN apk add --no-cache openssh bash xvfb xauth xterm xdotool xsetroot xclock \
        twm font-misc-misc xwininfo xrandr xdpyinfo \
 && ssh-keygen -A \
 && adduser -D -s /bin/bash demo \
 && echo 'demo:demo' | chpasswd \
 && mkdir -p /home/demo/proyecto /home/demo/.ssh \
 && echo 'hola desde el escritorio X11 de pruebas' > /home/demo/proyecto/LEEME \
 && chmod 700 /home/demo/.ssh \
 && chown -R demo:demo /home/demo \
 && sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config \
 && sed -i 's/^#\?AllowTcpForwarding.*/AllowTcpForwarding yes/' /etc/ssh/sshd_config

COPY x11-session.sh /usr/local/bin/x11-session.sh
COPY start.sh /usr/local/bin/start.sh
RUN chmod 755 /usr/local/bin/x11-session.sh /usr/local/bin/start.sh

EXPOSE 22
CMD ["/usr/local/bin/start.sh"]
