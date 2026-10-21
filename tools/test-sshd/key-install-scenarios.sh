#!/bin/sh
# Throwaway SSH destinations, one per case, for KeyInstallIntegrationTest
# (installing the public key on the host, ADR-0030). They come from the
# titan-test-sshd image (user demo, password demo) and listen on 127.0.0.1
# only; the shared titan-test-sshd container is never touched.
#
#   tools/test-sshd/key-install-scenarios.sh up     # create and start them
#   tools/test-sshd/key-install-scenarios.sh down   # remove them
#
# Ports:
#   2240 ok              password login, default sshd
#   2241 no-password     PasswordAuthentication and KbdInteractiveAuthentication off
#   2242 kbd-interactive password only through keyboard-interactive (PAM)
#   2243 home-writable   home is group-writable, StrictModes on
#   2244 read-only       home on a read-only mount
#   2245 disk-full       home on a full tmpfs
#   2246 pubkey-off      PubkeyAuthentication off
#   2247 keys-elsewhere  AuthorizedKeysFile outside the home
#   2248 ssh-not-owned   ~/.ssh belongs to root
#   2249 jump-target     only reachable through 2240 (as ki-target on the network)
set -eu

IMAGE=titan-test-sshd
NET=titan-ki
PREFIX=titan-ki

conf() { # <container> <sed expression>...
  c=$1; shift
  for e in "$@"; do docker exec "$c" sed -i "$e" /etc/ssh/sshd_config; done
  docker exec "$c" sh -c 'kill -HUP 1'
}

run() { # <name> <port> [docker run options...]
  name=$PREFIX-$1; port=$2; shift 2
  docker run -d --name "$name" --network "$NET" -p "127.0.0.1:$port:22" "$@" "$IMAGE" >/dev/null
  echo "$name"
}

up() {
  docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET" >/dev/null
  run ok 2240

  c=$(run no-password 2241)
  conf "$c" 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' '$a KbdInteractiveAuthentication no'

  # Alpine answers keyboard-interactive with the password only through PAM,
  # which is a separate sshd binary (sshd.pam).
  docker run -d --name "$PREFIX-kbd-interactive" --network "$NET" -p 127.0.0.1:2242:22 "$IMAGE" sh -c '
    apk add --no-cache openssh-server-pam >/dev/null &&
    sed -i "s/^#\?PasswordAuthentication.*/PasswordAuthentication no/" /etc/ssh/sshd_config &&
    printf "KbdInteractiveAuthentication yes\nUsePAM yes\n" >> /etc/ssh/sshd_config &&
    exec /usr/sbin/sshd.pam -D -e' >/dev/null

  c=$(run home-writable 2243)
  docker exec "$c" chmod 775 /home/demo

  # Remounting read-only needs CAP_SYS_ADMIN inside this one container.
  c=$(run read-only 2244 --cap-add SYS_ADMIN --mount type=tmpfs,destination=/home/demo)
  docker exec "$c" sh -c 'chown demo:demo /home/demo && chmod 700 /home/demo && mount -o remount,ro /home/demo'

  c=$(run disk-full 2245 --mount type=tmpfs,destination=/home/demo,tmpfs-size=65536)
  docker exec "$c" sh -c 'chown demo:demo /home/demo && chmod 700 /home/demo && (dd if=/dev/zero of=/home/demo/fill bs=4096 2>/dev/null || true)'

  c=$(run pubkey-off 2246)
  conf "$c" '$a PubkeyAuthentication no'

  c=$(run keys-elsewhere 2247)
  conf "$c" 's|^#\?AuthorizedKeysFile.*|AuthorizedKeysFile /etc/ssh/keys/%u|'

  c=$(run ssh-not-owned 2248)
  docker exec "$c" chown root:root /home/demo/.ssh

  docker run -d --name "$PREFIX-target" --network "$NET" --network-alias ki-target -p 127.0.0.1:2249:22 "$IMAGE" >/dev/null
  echo "$PREFIX-target"
}

down() {
  for c in $(docker ps -a --format '{{.Names}}' | grep "^$PREFIX-" || true); do docker rm -f "$c" >/dev/null; done
  docker network rm "$NET" >/dev/null 2>&1 || true
}

case "${1:-}" in
  up) down; up ;;
  down) down ;;
  *) echo "usage: $0 up|down" >&2; exit 2 ;;
esac
