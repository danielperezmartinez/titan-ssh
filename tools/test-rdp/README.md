# Destino RDP de pruebas

Destino desechable para probar las sesiones RDP de titan-ssh
([[ADR-0028 Cliente RDP embebido con IronRDP por un túnel SSH]]). Es un Debian
con:

- **OpenSSH** en el puerto 22 del contenedor (usuario `demo`, contraseña
  `demo`).
- **xrdp** en `127.0.0.1:3389` *dentro* del contenedor: seguridad TLS con el
  inicio de sesión del propio xrdp (sin NLA). Abre un escritorio Openbox con
  una terminal.
- **El servidor shadow de FreeRDP** en `127.0.0.1:3390` *dentro* del
  contenedor: NLA (CredSSP con NTLM, `demo`/`demo`) sobre una pantalla virtual
  de 1280×800 con una terminal.

Los dos servidores RDP solo escuchan dentro del contenedor: se llega a ellos
como lo hace la app, por la conexión SSH. **Publica solo el puerto SSH y solo
en `127.0.0.1`**; las credenciales son de prueba.

## Crearlo (una vez)

```sh
docker build -t titan-test-rdp tools/test-rdp
docker run -d --name titan-test-rdp -p 127.0.0.1:2231:22 titan-test-rdp
```

El puerto 2231 es un ejemplo: cualquiera libre vale (2222 y 2223 son del
servidor SSH de pruebas de `tools/test-sshd/`).

## Usarlo

```sh
docker start titan-test-rdp   # encender
docker stop titan-test-rdp    # apagar
```

En la app: un host `127.0.0.1:2231` (desde el emulador de Android,
`10.0.2.2:2231`) con usuario `demo` y contraseña `demo`, y una sesión de tipo
RDP con destino `localhost` y puerto `3390` (NLA) o `3389` (xrdp, con «Permitir
inicio de sesión TLS sin NLA» activado), usuario `demo` y contraseña `demo`.

Sin la app, la sonda del crate (`rdp/examples/probe.rs`) conecta a través de
un `ssh -L` y guarda el escritorio en una imagen:

```sh
ssh -N -L 127.0.0.1:43390:127.0.0.1:3390 -p 2231 demo@127.0.0.1 &
cd rdp && cargo run --example probe -- 127.0.0.1 43390 demo demo /tmp/nla.ppm
```
