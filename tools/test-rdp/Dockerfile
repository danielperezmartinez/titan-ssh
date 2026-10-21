# Throwaway RDP destination for the embedded RDP client (ADR-0028): an SSH
# server plus two RDP servers that only listen inside the container, so they
# are reached the way the app reaches them, through an SSH tunnel.
#
# - xrdp on port 3389: TLS security with the graphical login (no NLA).
# - FreeRDP's shadow server on port 3390: NLA (CredSSP with NTLM), mirroring
#   a virtual X display with a terminal on it.
#
# Test-only credentials: user `demo`, password `demo`, for SSH and both RDP
# servers. Publish only the SSH port, on loopback (see README.md).
FROM debian:trixie-slim

RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      openssh-server xrdp xorgxrdp dbus-x11 xterm openbox xvfb \
      freerdp3-shadow-x11 winpr3-utils procps \
 && rm -rf /var/lib/apt/lists/* \
 && useradd -m -s /bin/bash demo \
 && echo 'demo:demo' | chpasswd \
 && mkdir -p /run/sshd /home/demo/proyecto \
 && echo 'exec openbox-session' > /home/demo/.xsession \
 && mkdir -p /home/demo/.config/openbox \
 && echo 'xterm -geometry 100x30+40+40 &' > /home/demo/.config/openbox/autostart \
 && chown -R demo:demo /home/demo \
 && sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config \
 && sed -i 's/^#\?AllowTcpForwarding.*/AllowTcpForwarding yes/' /etc/ssh/sshd_config \
 && sed -i '0,/^port=.*/s//port=tcp:\/\/127.0.0.1:3389/' /etc/xrdp/xrdp.ini \
 && sed -i 's/^security_layer=.*/security_layer=tls/' /etc/xrdp/xrdp.ini \
 && mkdir -p /etc/titan-rdp \
 && winpr-hash3 -u demo -p demo -f sam > /etc/titan-rdp/SAM 2>/dev/null \
    || winpr-hash -u demo -p demo -f sam > /etc/titan-rdp/SAM

COPY start.sh /usr/local/bin/titan-test-rdp-start
RUN chmod 755 /usr/local/bin/titan-test-rdp-start

EXPOSE 22
CMD ["/usr/local/bin/titan-test-rdp-start"]
