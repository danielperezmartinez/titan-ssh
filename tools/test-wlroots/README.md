# Destino SSH con escritorio wlroots (sway) de pruebas

Destino desechable para probar el escritorio remoto contra un compositor
Wayland de la familia wlroots (ADR-0031 en `docs/Decisiones/`). Es el
servidor de `tools/test-sshd/` (usuario `demo`, contraseña `demo`) más sway
con el backend sin pantalla (`WLR_BACKENDS=headless`) y el renderizador por
software (`pixman`, sin GPU), con dos salidas virtuales:

- `HEADLESS-1`: 1280×800 a escala 1, en 0,0, con un reloj que cambia cada
  segundo.
- `HEADLESS-2`: 1024×768 a escala 2 (512×384 lógicos, para probar HiDPI), a
  su derecha, con un terminal.

Sin XWayland ni dispositivos de entrada propios: el agente trae un puntero y
un teclado virtuales. El agente encuentra la sesión por `/proc` (en el
contenedor no hay logind), con `WAYLAND_DISPLAY=wayland-1`,
`XDG_RUNTIME_DIR=/run/user/1000` y `XDG_CURRENT_DESKTOP=sway`.

**Solo escucha en `127.0.0.1`.** Las credenciales son de prueba: no publiques
el puerto en la red. Usa el **2228**; los demás puertos de 2222 a 2231 son de
otros destinos de pruebas.

## Crearlo

```sh
docker build -t titan-test-wlroots tools/test-wlroots
docker run -d --name titan-test-wlroots -p 127.0.0.1:2228:22 titan-test-wlroots
```

- Desde este equipo: `127.0.0.1:2228`.
- Desde el emulador de Android: `10.0.2.2:2228`.

Con `--build-arg ALPINE=3.22` sale sway 1.10 sobre wlroots 0.18, que solo
tiene `wlr-screencopy` (sin `ext-image-copy-capture`), para probar la vía de
respaldo. Para eso no hace falta publicar el puerto:

```sh
docker build --build-arg ALPINE=3.22 -t titan-test-wlroots:3.22 tools/test-wlroots
docker run -d --name titan-test-wlroots-322 titan-test-wlroots:3.22
```

## Probar el escritorio remoto

Como en `tools/test-x11`: app y agente del canal canary
(`-PtitanChannel=canary`; agente suelto con
`CGO_ENABLED=0 go build -tags canary -o <ruta>/titan-agent ./cmd/titan-agent`).

### Tests de Go dentro del contenedor

Los tests `Live` de `internal/screen` hablan con sway directamente: captura
de las dos salidas comparada con `grim`, daños, puntero y cursor, teclado
(incluidos caracteres que no están en ninguna distribución) y cambios de
resolución y de monitores.

```sh
cd agent
CGO_ENABLED=0 go test -c -o /tmp/screen.test ./internal/screen
docker cp /tmp/screen.test titan-test-wlroots:/tmp/screen.test
docker exec -u demo -e TITAN_WAYLAND_LIVE=1 -e XDG_RUNTIME_DIR=/run/user/1000 \
  -e WAYLAND_DISPLAY=wayland-1 -e SWAYSOCK=/run/user/1000/sway-ipc.1000.<pid>.sock \
  titan-test-wlroots /tmp/screen.test -test.run Live -test.v
```

El `SWAYSOCK` exacto sale de `docker exec titan-test-wlroots ls /run/user/1000`.
Con `-e TITAN_WAYLAND_PAINT_CURSOR=1` se prueba el cursor pintado en la imagen
en lugar de su sesión de captura propia.

### Test de integración de Kotlin

`ScreenTransportIntegrationTest` contra este destino, con una clave de prueba
copiada a `/home/demo/.ssh/authorized_keys` (como en `tools/test-sshd`):

```sh
./gradlew :shared:desktopTest --tests '*ScreenTransportIntegrationTest' --rerun-tasks \
  -PtitanSshTestHost=127.0.0.1 -PtitanSshTestPort=2228 -PtitanSshTestUser=demo \
  -PtitanSshTestKey=<clave> -PtitanAgentBin=<agente canary> -PtitanSshTestDesktop=1
```

## Mirar y tocar el escritorio desde dentro

```sh
docker exec -u demo -e XDG_RUNTIME_DIR=/run/user/1000 -e WAYLAND_DISPLAY=wayland-1 titan-test-wlroots wlr-randr
docker exec -u demo -e XDG_RUNTIME_DIR=/run/user/1000 -e WAYLAND_DISPLAY=wayland-1 titan-test-wlroots grim /tmp/shot.png
```

`swaymsg` necesita además `SWAYSOCK`. El registro de sway está en
`/tmp/sway.log`; si sway termina (`swaymsg exit`), se vuelve a arrancar solo.

Es un contenedor desechable: se borra con `docker rm -f titan-test-wlroots`.
