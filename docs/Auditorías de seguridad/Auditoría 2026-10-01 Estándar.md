---
Nombre: 'Auditoría 2026-10-01 Estándar'
Tipo: 'Estándar'
Estado: 'Cerrada'
Fecha: 2026-10-01
Versión auditada: 'v0.1.0-beta.9 + 16 commits (3fbe9e9), con el mouse pad sin publicar'
Resumen: 'Segunda auditoría, pedida por el usuario tras integrar el mouse pad en main. Niveles A y B: herramientas de dependencias, secretos y workflows, pruebas dinámicas contra el servidor de pruebas y un destino de dos usuarios en Docker, MobSF sobre la APK publicada y revisión del código nuevo. Cuatro hallazgos sensibles nuevos (1 de severidad alta y 3 baja) en avisos privados, dos avisos de la primera auditoría confirmados en pruebas, y endurecimiento público en tres tareas.'
Hallazgos privados: 4
Hallazgos públicos:
  - '[[Endurecer la entrada del mouse pad y del teclado del móvil]]'
  - '[[Endurecer la cadena de suministro]]'
  - '[[Actualizar sshj y BouncyCastle y retirar EdDSA-Java]]'
Fecha de creación: 2026-10-01T21:43:04+02:00
Última modificación: 2026-10-01T21:43:04+02:00
---

# Auditoría 2026-10-01 Estándar

> Nota pública. Los hallazgos sensibles se describen **solo** en su aviso
> privado de GitHub (regla 5 del [[README]]). Aquí van como referencia opaca.

## Alcance y método

- **Versión**: `main` en `3fbe9e9`, es decir, `v0.1.0-beta.9` más 16 commits.
  Incluye el mouse pad
  ([[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]],
  [[ADR-0017 Ayudante de escritorio con una copia gráfica del agente]]), que
  aún no está publicado. Para el análisis de la APK se usó la publicada,
  `v0.1.0-beta.9`.
- **Tipo**: estándar (niveles A y B de [[Auditorías de seguridad]]), pedida
  por el usuario.
- **Método**:
  - herramientas en Docker o en el equipo, sin tocar el árbol de trabajo;
  - pruebas dinámicas contra `titan-test-sshd` y contra un contenedor con dos
    usuarios locales;
  - un test temporal que no se ha versionado;
  - revisión del código nuevo desde la primera auditoría
    (`4691034..3fbe9e9`): parte la hizo un agente de revisión en solo
    lectura, y sus hallazgos se comprobaron en el código y, cuando fue
    posible, en pruebas.
- **Fuera de alcance esta vez**:
  - pruebas en un Windows real con dos cuentas estándar (las de la tarea
    programada y el escritorio bloqueado);
  - pruebas en el emulador (copias de seguridad, vista de recientes y el
    `ssh-audit` desde Android).

## Checklist ejecutado

| Punto | Resultado |
| --- | --- |
| A.1 Dependencias | `govulncheck`: nada, ni en Windows ni en Linux. Gradle sin lockfile, así que se resolvieron las 181 dependencias de la APK de release y del escritorio y se consultaron en OSV: avisos en BouncyCastle (`bcprov`, `bcpkix`) y `eddsa`, recogidos en una tarea pública. |
| A.2 Secretos | `gitleaks` sobre las 130 commits del historial: 2 falsos positivos (claves de ejemplo en la documentación de una skill de terceros). |
| A.3 Workflows | `zizmor`: 14 avisos de endurecimiento (credenciales persistentes del checkout, caché en un workflow que publica), con riesgo actual bajo. |
| A.4 Diff | Revisado el mouse pad: agente (`--input`, ayudante de escritorio, tarea programada, copia gráfica), protocolo de entrada e interfaz. Hallazgos privados. |
| A.5 Avisos abiertos | Ninguno corregido todavía. |
| B.1 Algoritmos | `ssh-audit -c` contra el motor de escritorio. Confirma un aviso de la primera auditoría. |
| B.2 Identidad del servidor | Test temporal con un `known_hosts` preparado. Confirma un aviso de la primera auditoría. Una clave distinta del mismo tipo se rechaza bien. |
| B.3 Opciones | Revisadas por código, incluidas las del editor de sesiones mouse pad. |
| B.4 Agente con dos usuarios | Contenedor con dos cuentas: permisos `700`/`600` correctos, el otro usuario no lee el token y se rechaza su directorio de estado. Hallazgo privado. |
| B.5 Contenido hostil | Sin cambios desde la primera auditoría. |
| B.6 Secretos | Lo tecleado en el mouse pad no se registra ni se guarda. Mejora pública del teclado del móvil. |
| B.7 Android | MobSF sobre la APK publicada: 57/100, sin trackers, permisos `INTERNET` y `ACCESS_NETWORK_STATE`, sin componentes exportados propios salvo el lanzador. Firma solo v2. Confirma un aviso de la primera auditoría. Lint de la variante release: un único aviso de seguridad (`TrustAllX509TrustManager`), dentro de `bcpkix`, en código que la app no usa para TLS. |
| B.8 Agente estático | `go test -race ./...` en Linux: todo pasa. `gosec`: 70 avisos sin impacto (conversiones de enteros, errores de `Close()` sin comprobar, rutas y subprocesos con datos del propio usuario). |
| B.9 Dependencias al día | sshj 0.41.1 y BouncyCastle 1.86 publicadas, y el proyecto en 0.39.0 y 1.78.1. `golang.org/x/sys` al día. JNA 5.13 (transitiva) sin avisos. |
| B.10 MASVS L1 | STORAGE: copias de seguridad (aviso existente). CRYPTO: algoritmos (aviso existente) y dependencias (tarea). AUTH: sin cambios. NETWORK: sin tráfico en claro; el único HTTP es la descarga del agente por HTTPS con SHA-256 fijado. PLATFORM: un componente exportado (el lanzador). CODE: R8 activo, sin depuración en release. |

## Hallazgos privados

| Referencia | Severidad | Aviso | Estado |
| --- | --- | --- | --- |
| SEC-2026-10 | Alta | GHSA-ggv3-75c4-q7c9 | Abierto |
| SEC-2026-11 | Baja | GHSA-m5vj-32pq-c649 | Abierto (código sin publicar) |
| SEC-2026-12 | Baja | GHSA-hgh9-w5p6-g4w2 | Abierto |
| SEC-2026-13 | Baja | GHSA-824q-2q2r-v4f6 | Abierto |

Avisos de la [[Auditoría 2026-10-01 Completa]] actualizados en esta:

- SEC-2026-01 y SEC-2026-06 quedan **confirmados en pruebas**, con el
  resultado anotado en su aviso.
- La APK publicada confirma el SEC-2026-07.

Varios avisos afectan al mouse pad, que aún no está publicado. Lo que haga
falta se arreglará antes de su primera versión, y por eso
[[Mouse pad en destinos Windows]] tiene un criterio que bloquea su
publicación.

## Hallazgos públicos

- [[Endurecer la entrada del mouse pad y del teclado del móvil]]:
  modificadores que pueden quedar pulsados en el PC, y aprendizaje del
  teclado del móvil.
- [[Endurecer la cadena de suministro]]: avisos de `zizmor`,
  `.gitleaksignore` y firma APK v3, añadidos a la tarea.
- [[Actualizar sshj y BouncyCastle y retirar EdDSA-Java]]: la tabla de
  avisos públicos de OSV y la versión mínima de BouncyCastle, añadidas a la
  tarea.

## Lo que está bien

- **Agente en un destino compartido**: el directorio de estado (`700`) y el
  token (`600`) no son legibles por otros usuarios, y el agente rechaza un
  directorio de estado ajeno o accesible por otros.
- **Mouse pad**:
  - la entrada solo se acepta tras el preámbulo con token, y el daemon y el
    ayudante ignoran las tramas del otro tipo de sesión;
  - la tarea programada usa `schtasks.exe` por ruta completa, el token
    interactivo con privilegios mínimos, sin disparadores (solo bajo
    demanda) y XML escapado;
  - la copia gráfica del agente se regenera y se compara en cada arranque;
  - no hay `uiAccess`, así que no llega al escritorio seguro, a la pantalla
    de bloqueo ni a ventanas elevadas;
  - la conexión pasa por la misma verificación TOFU que el terminal.
- **APK publicada**: sin trackers ni permisos de más, sin tráfico en claro,
  firmada con la clave de release.
- **Agente**: `go test -race` limpio, y `govulncheck` sin avisos en la
  toolchain de CI (Go 1.27.x).

## Cambios para el checklist

Se incorporan a [[Auditorías de seguridad]] en el mismo cambio:

- A.1: sin lockfile, cómo resolver las dependencias con Gradle y consultarlas
  todas de una vez en la API de OSV.
- A.2: los falsos positivos conocidos de `gitleaks`, hasta que exista el
  `.gitleaksignore`.
- B.1: cómo conectar el motor a `ssh-audit -c` con el test de integración
  opcional, sin la interfaz.
- B.7: MobSF en Docker sobre la APK **publicada** con su API.
- Las comprobaciones de regresión de los avisos nuevos esperan a que se
  publiquen (regla 5).
