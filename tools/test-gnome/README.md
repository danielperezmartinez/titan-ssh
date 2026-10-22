# Destino SSH con escritorio GNOME Wayland de pruebas

Destino desechable para probar el escritorio remoto y el mouse pad contra GNOME
con Wayland (ADR-0031 en `docs/Decisiones/`): el portal RemoteDesktop +
ScreenCast, la API propia de Mutter (acceso desatendido) y PipeWire. Es un
Debian trixie con usuario `demo` (contraseña `demo`), GNOME Shell sin pantalla
(`gnome-shell --headless`) con dos monitores de 1280×800, el portal de GNOME,
PipeWire, WirePlumber y un reloj que cambia cada segundo. No necesita GPU
(llvmpipe). No hay logind: el agente encuentra la sesión por `/proc`.

**Solo escucha en `127.0.0.1`.** Las credenciales son de prueba: no publiques
el puerto en la red. Usa el puerto 2226 (los demás son de otros destinos de
pruebas).

## Crearlo

```sh
docker build -t titan-test-gnome tools/test-gnome
docker run -d --name titan-test-gnome -p 127.0.0.1:2226:22 --memory 2g titan-test-gnome
```

- Desde este equipo: `127.0.0.1:2226`.
- Desde el emulador de Android: `10.0.2.2:2226`.

## Probar sin nadie delante

El portal pregunta en la pantalla del destino, que aquí nadie ve. Con el
acceso desatendido, el agente usa la API de Mutter y no hay diálogo:

```sh
docker exec -u demo -e DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus titan-test-gnome \
  /tmp/titan-agent --remote-desktop unattended-on
```

(o la casilla «Permitir sin nadie delante» de la sesión «escritorio» en la
app). Para probar el diálogo del portal, se acepta con clics desde otra sesión
desatendida: hay que activar «Allow Remote Interaction», elegir los monitores
y pulsar «Share».

## Mirarlo desde dentro

`agent/cmd/titan-screenprobe` hace de cliente del escritorio remoto y del
mouse pad sin la app: guarda la imagen en PNG, mueve, hace clic, escribe y
pulsa teclas.

```sh
go build -o /tmp/screenprobe ./agent/cmd/titan-screenprobe
/tmp/screenprobe -out gnome.png -for 5s -- docker exec -i -u demo \
  -e DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus titan-test-gnome /tmp/titan-agent --screen
```

Es un contenedor desechable: se borra con `docker rm -f titan-test-gnome`.
