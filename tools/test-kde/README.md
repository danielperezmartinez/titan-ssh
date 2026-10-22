# Destino SSH con escritorio KDE Wayland de pruebas

Destino desechable para probar el escritorio remoto y el mouse pad contra KDE
Plasma con Wayland (ADR-0031 en `docs/Decisiones/`): la vía del portal
RemoteDesktop + ScreenCast y PipeWire. Es un Debian trixie con usuario `demo`
(contraseña `demo`), KWin sin pantalla (`kwin_wayland --virtual`) con dos
monitores de 1280×800, el portal de KDE, PipeWire, WirePlumber y un terminal.
No hay logind: el agente encuentra la sesión por `/proc`.

**Solo escucha en `127.0.0.1`.** Las credenciales son de prueba: no publiques
el puerto en la red. Usa el puerto 2225 (los demás son de otros destinos de
pruebas).

## Necesita una GPU Intel o AMD

KWin solo captura la pantalla cuando compone con OpenGL, y su backend virtual
solo lo hace sobre un nodo DRM. Sin GPU compone con QPainter y el portal
responde que no hay captura (`zkde_screencast_unstable_v1 does not seem to be
available`). Con una NVIDIA con el driver propietario tampoco sirve: el Mesa
del contenedor no la maneja y KWin se cae al reservar los búferes de la
captura. En ese caso, KDE se prueba en un Plasma real.

## Crearlo

```sh
docker build -t titan-test-kde tools/test-kde
docker run -d --name titan-test-kde -p 127.0.0.1:2225:22 \
  --device /dev/dri -v /run/udev:/run/udev:ro titan-test-kde
```

- Desde este equipo: `127.0.0.1:2225`.
- Desde el emulador de Android: `10.0.2.2:2225`.
- `KWIN_WAYLAND_NO_PERMISSION_CHECKS=1` va en la sesión: sin systemd, KWin no
  sabe que el backend del portal tiene derecho a sus protocolos de captura y
  de entrada. Es solo para el contenedor.

## Saltarse el diálogo

El portal pregunta en la pantalla del destino, que aquí nadie ve. Para las
pruebas se preautoriza al agente, como hace el acceso desatendido de la app:

```sh
docker exec -u demo -e DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus titan-test-kde \
  /tmp/titan-agent --remote-desktop unattended-on
```

Es un contenedor desechable: se borra con `docker rm -f titan-test-kde`.
