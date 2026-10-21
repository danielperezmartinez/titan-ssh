---
Nombre: 'Sesión RDP embebida con IronRDP'
Estado: 'En curso'
Resumen: 'Nuevo tipo de sesión RDP (opción C): el escritorio de un servidor RDP del destino (Windows, xrdp) en una pestaña, por un canal direct-tcpip de la conexión SSH y con un cliente embebido basado en IronRDP (Rust) vía JNI. Incluye la biblioteca nativa y su build para Android y escritorio, la prueba de concepto (NLA, fotogramas, teclado, ratón y táctil) y la integración en el modelo de sesión, el editor y las pestañas.'
Decisiones: 'Decisión técnica en [[ADR-0028 Cliente RDP embebido con IronRDP por un túnel SSH]] (Propuesta). Portapapeles, audio, unidades y multimonitor fuera salvo que el usuario diga otra cosa. Destino de pruebas: contenedor tools/test-rdp (sshd + xrdp con TLS + servidor shadow de FreeRDP con NLA).'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-21T11:00:00+02:00
Última modificación: 2026-10-21T11:00:00+02:00
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

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
