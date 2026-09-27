# Throwaway SSH destination for manual tests of titan-ssh (Android emulator,
# desktop app) and for the opt-in Kotlin integration tests. Everything is baked
# in at build time, so the container can be stopped and started again at will.
#
# Test-only credentials: user `demo`, password `demo`. Publish the port on
# loopback only (see README.md); never expose it on the network.
FROM alpine:3.20

RUN apk add --no-cache openssh bash \
 && ssh-keygen -A \
 && adduser -D -s /bin/bash demo \
 && echo 'demo:demo' | chpasswd \
 && mkdir -p /home/demo/proyecto /home/demo/.ssh \
 && echo 'hola desde el servidor de pruebas' > /home/demo/proyecto/LEEME \
 && chmod 700 /home/demo/.ssh \
 && chown -R demo:demo /home/demo \
 && sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config

EXPOSE 22
CMD ["/usr/sbin/sshd", "-D", "-e"]
