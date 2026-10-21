# Destino SSH con escritorio X11 de pruebas

Destino desechable para probar el escritorio remoto y el mouse pad contra
Linux X11 (ADR-0029 en `docs/Decisiones/`). Es el
servidor de `tools/test-sshd/` (usuario `demo`, contraseña `demo`) más un
servidor X virtual (Xvfb) en el display `:1`, de 1280×800, del usuario `demo`
y protegido con una cookie `MIT-MAGIC-COOKIE-1` en `~/.Xauthority`. Lleva un
gestor de ventanas (twm), un reloj que cambia cada segundo y un terminal.

**Solo escucha en `127.0.0.1`.** Las credenciales son de prueba: no publiques
el puerto en la red. No uses el puerto 2222 (el del servidor permanente) ni
el 2223 (pruebas de actualización del agente).

## Crearlo

```sh
docker build -t titan-test-x11 tools/test-x11
docker run -d --name titan-test-x11 -p 127.0.0.1:2224:22 titan-test-x11
```

- Desde este equipo: `127.0.0.1:2224`.
- Desde el emulador de Android: `10.0.2.2:2224`.

## Probar el escritorio remoto

El escritorio remoto está a prueba en la canary: solo la app y el agente del
canal canary lo ofrecen.

- App: build con `-PtitanChannel=canary` (en el emulador, la de debug con
  `./gradlew :androidApp:assembleDebug -PtitanChannel=canary`).
- Agente suelto para `TITAN_AGENT_BIN`:
  `cd agent && CGO_ENABLED=0 go build -tags canary -o <ruta>/titan-agent ./cmd/titan-agent`.
  Sin `-tags canary`, `--screen` responde `E_SCREEN_UNSUPPORTED`.

## Mirar y tocar el escritorio desde dentro

```sh
docker exec -u demo -e DISPLAY=:1 -e XAUTHORITY=/home/demo/.Xauthority titan-test-x11 xdotool getmouselocation
docker exec -u demo -e DISPLAY=:1 -e XAUTHORITY=/home/demo/.Xauthority titan-test-x11 xrandr
```

Es un contenedor desechable: se borra con `docker rm -f titan-test-x11`.
Reiniciarlo crea una cookie nueva y vuelve a abrir las ventanas.
