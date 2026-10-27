---
Nombre: 'Sesión RDP embebida con IronRDP'
Estado: 'Archivada'
Resumen: 'Descartada el 2026-10-27 y retirada entera del código: el usuario eligió el escritorio remoto propio (opción D). Era un nuevo tipo de sesión RDP (opción C): el escritorio de un servidor RDP del destino (Windows, xrdp) en una pestaña, por un canal direct-tcpip de la conexión SSH y con un cliente embebido basado en IronRDP (Rust) vía JNI. Incluye la biblioteca nativa y su build para Android y escritorio, la prueba de concepto (NLA, fotogramas, teclado, ratón y táctil) y la integración en el modelo de sesión, el editor y las pestañas.'
Decisiones: 'Decisión técnica en [[ADR-0028 Cliente RDP embebido con IronRDP por un túnel SSH]] (Rechazada el 2026-10-27). Portapapeles, audio, unidades y multimonitor fuera salvo que el usuario diga otra cosa. Destino de pruebas: contenedor tools/test-rdp (sshd + xrdp con TLS + servidor shadow de FreeRDP con NLA).'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-21T11:00:00+02:00
Última modificación: 2026-10-27T10:00:00+01:00
---

# Sesión RDP embebida con IronRDP

## Objetivo

Poder abrir el escritorio de un equipo en una pestaña de titan-ssh por la misma
conexión SSH que ya se usa para la terminal, sin instalar nada de titan-ssh en
el destino ni exponer el puerto RDP a la red. Es la opción C de escritorio
remoto; la opción D (escritorio propio sobre el agente) se estudia en otra
tarea.

## Criterios de finalización

- Biblioteca nativa `rdp/` (IronRDP) compilada para Android (arm64-v8a y
  x86_64) y escritorio (Linux x64; Windows x64 en CI) desde Gradle, opcional en
  desarrollo y obligatoria en release.
- Sesión de tipo RDP en el modelo (`SessionType.RDP` + `RdpSettings`), sin
  cambiar la versión de configuración.
- Editor de sesión con host/puerto RDP vistos desde el destino, usuario,
  dominio, contraseña (SecretStore) y la opción de permitir TLS sin NLA.
- Pestaña que conecta por un canal direct-tcpip, pide confianza en el
  certificado (huella SHA-256) antes de mandar credenciales, pinta el
  escritorio y manda teclado y ratón (escritorio) o toques y teclado del
  sistema (Android).
- Probado contra el contenedor de pruebas (NLA y TLS) en escritorio y en el
  emulador, con capturas.
- CI de release y canary con la toolchain de Rust y el NDK.

## Avance

- 2026-10-21: el escritorio sigue el tamaño del panel en la app de escritorio
  (Display Control), también con xrdp (ver la consecuencia de
  [[ADR-0028 Cliente RDP embebido con IronRDP por un túnel SSH]]). Los 11 tests
  de integración RDP pasan contra `tools/test-rdp`.
- 2026-10-21: el usuario decide que la estable no lleve nada de las opciones C
  y D hasta elegir una. `SessionType.RDP` es `experimental` (mecanismo común con
  la opción D, que solo ofrece el tipo en la canary), la biblioteca nativa
  solo se compila con `titanChannel=canary` y CI solo instala Rust y el NDK en
  los runs de la canary. En el editor el tipo se llama «rdp», para no
  confundirse con el «escritorio» de la opción D. Retirada prevista en §8 de la
  ADR.
- 2026-10-21: probado en el emulador con una build de debug de la canary
  (x86_64) contra xrdp por `10.0.2.2:2231`: editor, confianza en la clave de
  host y en el certificado antes de mandar credenciales, escritorio pintado,
  teclado del sistema y touchpad.
- 2026-10-21: también probado en el emulador el modo NLA (servidor shadow en
  el 3390) y la reconexión: al cortar las conexiones SSH en el destino, las dos
  pestañas vuelven solas y el escritorio responde.
- 2026-10-21: rebasada sobre `main` con la opción D ya integrada. El editor de
  la canary ofrece «escritorio» (D) y «rdp» (C); pasan los tests de las dos
  (`:shared:desktopTest`, RDP y X11 de integración, agente con y sin
  `-tags canary`, motor Rust).
- 2026-10-27: el usuario elige la opción D y descarta esta. Se aplica la
  retirada de §8 de la ADR (ver Resultado).

## Verificación

De la retirada (2026-10-27):

- `./gradlew :shared:desktopTest` pasa, con un test nuevo en
  `ConfigMigrationTest` que comprueba que se descartan las sesiones RDP.
- Build de la canary (`-PtitanChannel=canary`): `:androidApp:assembleDebug` y
  `:desktopApp:createDistributable` compilan; la APK ya no lleva
  `libtitan_rdp.so`.
- `go vet ./...` del agente, con y sin `-tags canary`.
- `release.yml` vuelve a ser idéntico al anterior a esta tarea (sin Rust ni
  NDK).

## Resultado

Se probó en la canary y se descartó: el usuario se quedó con el escritorio
remoto propio ([[ADR-0029 Escritorio remoto propio a través del agente]]),
que estaba terminado. Se retiró todo lo de esta opción, como preveía §8 de
[[ADR-0028 Cliente RDP embebido con IronRDP por un túnel SSH]]: el crate
`rdp/`, el paquete `rdp` y `RdpView` de `shared`, `SessionType.RDP` y
`RdpSettings`, `SshSession.openDirect()` con `SshDirectChannel` y
`SshDirectRefused` (solo los usaba RDP), las tareas de Gradle de Android y
escritorio (y el `ndkVersion`), los pasos de Rust y NDK de `release.yml`,
`tools/test-rdp/`, la sección de IronRDP de `THIRD_PARTY_NOTICES.md` y la
entrada `RdpScreen` del catálogo técnico. `ConfigMigration` quita las
sesiones de tipo RDP que guardó la canary (sin ellas la configuración no se
podría leer); el campo `rdp` de las demás se ignora al leer. La contraseña que
alguna de esas sesiones guardara en el SecretStore se queda allí sin uso.
