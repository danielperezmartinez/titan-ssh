---
Nombre: 'Endurecer la cadena de suministro'
Estado: 'Pendiente'
Resumen: 'Cerrar los huecos de la cadena de suministro que encontró la [[Auditoría 2026-10-01 Completa]]: no hay avisos automáticos de dependencias vulnerables, el wrapper de Gradle no fija el checksum de la distribución, no se verifican las dependencias descargadas y los artefactos del Release no llevan firma ni atestación de procedencia (el SHA256SUMS solo prueba integridad frente a la propia página del Release).'
Decisiones: ''
Bloqueada: []
Fecha de creación: 2026-10-01T18:11:30+02:00
Última modificación: 2026-10-01T21:43:04+02:00
---

# Endurecer la cadena de suministro

## Objetivo

Que una dependencia vulnerable o manipulada, o un artefacto alterado, se
detecten antes de llegar a los usuarios. Todo con servicios gratuitos (regla 4
del [[README]]). Ya está bien: las acciones de GitHub fijadas por SHA, los
permisos mínimos por defecto (`contents: read`) y el SHA-256 de los binarios
del agente fijado dentro de la app
([[ADR-0010 Empaquetado del agente y descarga bajo demanda]]).

## Criterios de finalización

- [ ] **Dependabot** (`.github/dependabot.yml`) para los ecosistemas `gradle`,
  `gomod` (`agent/`) y `github-actions`, con agrupación para no inundar de PR.
  👤 Activar los avisos de Dependabot en *Settings* → *Security*.
- [ ] **Wrapper de Gradle**: `distributionSha256Sum` en
  `gradle/wrapper/gradle-wrapper.properties`, y comprobación del
  `gradle-wrapper.jar` en CI (`gradle/actions/wrapper-validation`, fijada por
  SHA).
- [ ] **Verificación de dependencias** de Gradle
  (`gradle/verification-metadata.xml` con checksums SHA-256), o una decisión
  razonada de no hacerlo si el coste de mantenimiento no compensa.
- [ ] **Procedencia de los artefactos**: atestaciones de build
  (`actions/attest-build-provenance`, gratis en repositorios públicos) para la
  APK, el AAB, los paquetes de escritorio y los binarios del agente, y su
  comprobación (`gh attestation verify`) documentada en el `README.md` de la
  raíz. Solo el job que publica recibe `id-token: write` y
  `attestations: write`.
- [ ] Corregir lo que dio `zizmor` en la [[Auditoría 2026-10-01 Estándar]]
  (14 avisos, todos en `release.yml`):
  - **`artipacked`** en los 7 `actions/checkout`: añadir
    `persist-credentials: false`. Ningún artefacto sube el directorio de
    trabajo (solo `dist/` y los binarios del agente), así que hoy no se filtra
    el token, pero así deja de depender de eso.
  - **`cache-poisoning`** en `setup-go` y `setup-gradle` de un workflow que
    publica: desactivar la caché en los jobs que generan artefactos
    publicados (`cache: false`, `cache-disabled: true`), o justificar por qué
    no hace falta. No hay workflows de `pull_request` que escriban caché, así
    que el riesgo actual es bajo.
- [ ] `.gitleaksignore` para los dos falsos positivos de `gitleaks` (claves de
  ejemplo de AWS en `.agents/skills/golang-security/references/secrets.md`),
  para que el escaneo del nivel A salga limpio.
- [ ] **Firma APK v3**: la APK publicada solo lleva el esquema v2 (MobSF, en
  la [[Auditoría 2026-10-01 Estándar]]). Activar v3 (`enableV3Signing`) para
  poder rotar la clave de firma algún día sin que los usuarios tengan que
  desinstalar, y comprobarlo con `apksigner verify -v`.
- [ ] La firma del MSI sigue en
  [[Firma de código Windows con SignPath Foundation]]; aquí no se duplica.

## Verificación

<Se rellena al completar: un PR de Dependabot de prueba, un run del workflow
con atestaciones y su verificación sobre un artefacto descargado.>

## Resultado

<Se rellena al completar.>
