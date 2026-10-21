---
Nombre: 'Sesión RDP embebida con IronRDP'
Estado: 'En curso'
Resumen: 'Nuevo tipo de sesión RDP (opción C): el escritorio de un servidor RDP del destino (Windows, xrdp) en una pestaña, por un canal direct-tcpip de la conexión SSH y con un cliente embebido basado en IronRDP (Rust) vía JNI. Incluye la biblioteca nativa y su build para Android y escritorio, la prueba de concepto (NLA, fotogramas, teclado, ratón y táctil) y la integración en el modelo de sesión, el editor y las pestañas.'
Decisiones: 'Decisión técnica en [[ADR-0028 Cliente RDP embebido con IronRDP por un túnel SSH]] (Propuesta). Portapapeles, audio, unidades y multimonitor fuera salvo que el usuario diga otra cosa. Destino de pruebas: contenedor tools/test-rdp (sshd + xrdp con TLS + servidor shadow de FreeRDP con NLA).'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-21T11:00:00+02:00
Última modificación: 2026-10-21T14:30:00+02:00
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
- Pendiente: la prueba manual en la app de escritorio y el job de Windows en CI
  (solo se puede comprobar en un run de la canary).

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
