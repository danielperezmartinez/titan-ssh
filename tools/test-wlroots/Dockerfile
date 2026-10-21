# Throwaway SSH destination with a wlroots Wayland desktop, for testing the
# remote desktop against sway (ADR-0031 §2). It is the test-sshd image plus
# sway on the headless backend with the pixman renderer (no GPU), two
# virtual outputs (the second at scale 2, for HiDPI), a clock that changes
# every second and a terminal, all owned by the user `demo`.
#
# sway's file capability (cap_sys_nice, for realtime scheduling) is
# dropped: it is outside Docker's default set, and exec would fail with it.
#
# ALPINE picks the sway: edge has sway 1.12 on wlroots 0.20
# (ext-image-copy-capture with its cursor session); 3.22 has sway 1.10 on
# wlroots 0.18 (only wlr-screencopy), to test the fallback.
#
# Test-only credentials: user `demo`, password `demo`. Publish the port on
# loopback only (see README.md); never expose it on the network.
ARG ALPINE=edge
FROM alpine:${ALPINE}

RUN apk add --no-cache openssh bash sway foot font-dejavu wayland-utils \
        wlr-randr grim swaybg libcap-setcap \
 && setcap -r /usr/bin/sway \
 && ssh-keygen -A \
 && adduser -D -s /bin/bash demo \
 && echo 'demo:demo' | chpasswd \
 && mkdir -p /home/demo/proyecto /home/demo/.ssh /home/demo/.config/sway \
 && echo 'hola desde el escritorio sway de pruebas' > /home/demo/proyecto/LEEME \
 && chmod 700 /home/demo/.ssh \
 && sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config \
 && sed -i 's/^#\?AllowTcpForwarding.*/AllowTcpForwarding yes/' /etc/ssh/sshd_config

COPY sway-config /home/demo/.config/sway/config
COPY clock.sh /usr/local/bin/clock.sh
COPY sway-session.sh /usr/local/bin/sway-session.sh
COPY start.sh /usr/local/bin/start.sh
RUN chmod 755 /usr/local/bin/clock.sh /usr/local/bin/sway-session.sh /usr/local/bin/start.sh \
 && chown -R demo:demo /home/demo

EXPOSE 22
CMD ["/usr/local/bin/start.sh"]
