---
Nombre: 'JDK de los paquetes de escritorio con el parche de Java de octubre de 2026'
Estado: 'Hecha'
Estado desde: 2026-10-28
Resumen: 'Los paquetes de escritorio (MSI, deb, rpm, tar.gz) llevaban dentro Temurin 21.0.12, que confía en CA intermedias de eMudhra comprometidas (CVE-2026-91403, JSSE). Desde la 1.4.0 llevan el Microsoft Build of OpenJDK 21.0.13.0.2, la primera build de 21 con la lista de bloqueo de octubre de 2026, descargado en CI con su SHA-256 fijado.'
Decisiones: 'Microsoft 21.0.13.0.2 en lugar de Temurin, que no trae la lista de bloqueo. setup-java todavía no la ofrece en su manifiesto de Microsoft (llega a 21.0.11), así que los jobs linux y windows la descargan, comprueban su SHA-256 y la pasan como jdk-file. Se publica en la 1.4.0 junto con todo lo que había en main desde la 1.3.0 (decisión del usuario).'
Bloqueada: []
Prueba: ''
Esperando a: ''
Versión: '1.4.0'
Fecha de creación: 2026-10-28T14:30:00+01:00
Última modificación: 2026-10-28T16:30:00+01:00
---

# JDK de los paquetes de escritorio con el parche de Java de octubre de 2026

## Objetivo

La app de escritorio abre TLS a GitHub con el JRE que lleva dentro (actualizador
y descarga del agente). El cacerts de OpenJDK 21.0.12 confía en las raíces
emSign, y CVE-2026-91403 (9.1, JSSE) cubre ocho CA intermedias de eMudhra
comprometidas. El arreglo de OpenJDK (cambio 8395142, "CA Enhancements") solo
está en 21.0.13.0.2. El manifiesto firmado del actualizador y la comprobación
SHA-256 de los agentes lo atenúan; SSH no pasa por JSSE. El agente Go no cambia.

## Criterios de finalización

- El MSI, el deb, el rpm y el tar.gz llevan un runtime con la lista de bloqueo
  de octubre de 2026.
- La canary sigue el mismo cambio (mismo workflow).
- Versión estable publicada.

## Verificación

- Microsoft 21.0.13.0.2 (linux-x64): `lib/security/blocked.certs` tiene 86
  huellas frente a las 38 de Temurin 21.0.13+9. Las nuevas que se consultaron
  en crt.sh son intermedias de eMudhra (EM DV/OV/EV TLS CA y EM OV CS CA, G3B y
  G3C, también las firmadas por AAA Certificate Services). No quita la raíz
  emSign Root CA – G1 ni añade política de desconfianza.
- Run manual del workflow sobre la rama (sin publicar): los cuatro paquetes
  llevan `JAVA_VERSION="21.0.13.0.2"` en `runtime/release` y el mismo
  `blocked.certs` que el JDK de Microsoft (en el MSI, con finales de línea
  CRLF). El tar.gz arranca en Linux con una carpeta personal vacía.
- Canary 1.3.1-canary.42 y Release v1.4.0: jobs linux y windows con
  `jdk.tar.gz: OK` / `jdk.zip: OK` y setup-java en `jdkfile` 21.0.13.

## Resultado

`.github/workflows/release.yml`: variables `DESKTOP_JDK_*` (versión, URL y
SHA-256 de Linux y Windows) y, en los jobs `linux` y `windows`, un paso que
descarga y comprueba el JDK antes de `setup-java` con `distribution: jdkfile`.
Los demás jobs siguen en Temurin `21`. Cuando el manifiesto de Microsoft de
setup-java incluya la versión, se puede volver a `distribution: microsoft` con
la versión exacta. Publicado en la 1.4.0.
