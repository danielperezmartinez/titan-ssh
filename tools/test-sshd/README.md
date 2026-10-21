# Servidor SSH de pruebas

Destino SSH desechable para probar titan-ssh a mano (emulador de Android, app
de escritorio) y para los tests de integración opcionales de Kotlin. Es un
Alpine con OpenSSH y bash, con el usuario `demo` (contraseña `demo`) y la
carpeta `~/proyecto`.

**Solo escucha en `127.0.0.1`.** Las credenciales son de prueba: no publiques
el puerto en la red.

## Crearlo (una vez)

```sh
docker build -t titan-test-sshd tools/test-sshd
docker run -d --name titan-test-sshd -p 127.0.0.1:2222:22 titan-test-sshd
```

## Usarlo

```sh
docker start titan-test-sshd   # encender
docker stop titan-test-sshd    # apagar
```

- Desde este equipo: `127.0.0.1:2222`.
- Desde el emulador de Android: `10.0.2.2:2222`.
- La clave de host se genera al construir la imagen y no cambia mientras no se
  reconstruya, así que la app no vuelve a pedir que la aceptes.

Para entrar con clave en vez de contraseña (p. ej. en los tests de
integración), copia una clave pública de pruebas:

```sh
docker cp <clave-de-pruebas>.pub titan-test-sshd:/home/demo/.ssh/authorized_keys
```

El reenvío de puertos (túneles) está activado, al contrario que en el Alpine
de serie. En un contenedor creado antes de este cambio se activa sin
reconstruirlo, y así se conserva la clave de host:

```sh
docker exec titan-test-sshd sh -c "sed -i 's/^AllowTcpForwarding no/AllowTcpForwarding yes/' /etc/ssh/sshd_config && kill -HUP 1"
```

Los tests de túneles (`SshjForwardingIntegrationTest`) también comprueban los
rechazos si se desactiva (`AllowTcpForwarding no`) y se pasa
`-PtitanSshTestNoForwarding=true`.

## Casos de error de la instalación de claves

`key-install-scenarios.sh` crea, a partir de esta imagen, un contenedor
desechable por cada caso de `KeyInstallIntegrationTest` (instalar la clave en
el host desde la app): contraseña desactivada, solo `keyboard-interactive`
(PAM), home con escritura para el grupo, home de solo lectura, disco lleno,
`PubkeyAuthentication no`, `AuthorizedKeysFile` fuera de la home, `~/.ssh` de
root y un destino al que solo se llega por salto. Escuchan en `127.0.0.1`,
puertos 2240-2249 (la lista está en el script); `titan-test-sshd` no se toca.

```sh
tools/test-sshd/key-install-scenarios.sh up
./gradlew :shared:desktopTest --tests '*KeyInstallIntegrationTest' -PtitanKeyInstallTest=1
tools/test-sshd/key-install-scenarios.sh down
```

Desde el emulador se llega a ellos por `10.0.2.2:<puerto>`.

No sustituye a las pruebas contra destinos reales (otros sistemas, Windows,
redes reales): sirve para probar la app y su interfaz.
