---
Nombre: 'Firma y configuración de release Android'
Estado: 'Pendiente'
Resumen: 'Preparar la build de release de Android. Crear una clave de firma propia (keystore) que nunca va al repositorio, vive en los secretos de CI y tiene copia de seguridad del usuario, y que servirá también como clave de subida si algún día se usa Play. Añadir signingConfig leído de variables de entorno y el buildType release, decidir R8 (con reglas para sshj/BouncyCastle), desactivar el bloque de dependencias que añade AGP (IzzyOnDroid y F-Droid lo piden) y generar APK universal y AAB. Verificar la APK de release en el Pixel.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §5–6.'
Bloqueada:
  - "[[Cambiar el identificador de la app a io.github]]"
  - "[[Icono y recursos gráficos de la app]]"
  - "[[Versionado único desde tag de git]]"
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-23T22:50:00+02:00
---

# Firma y configuración de release Android

## Objetivo

Generar desde CI una APK de release firmada, estable en el tiempo, que acepten
Obtainium e IzzyOnDroid y que sirva para Play en el futuro.

## Contexto

`androidApp/build.gradle.kts` no tiene `signingConfigs`, ni `buildTypes.release`
configurado, ni R8. No hay `java`/`keytool` en el PATH: usar el JBR de Android
Studio ([[build-toolchain]]). La criptografía SSH en Android depende de
BouncyCastle y del Keystore ([[android-ssh-crypto]]), sensible a R8.

## Criterios de finalización

- **Keystore de release** creado con `keytool` (RSA 4096 o EC, validez de 25
  años o más). **Lo genera y custodia el usuario**: la contraseña no pasa por el
  agente.
  - Copia de seguridad fuera del repositorio (gestor de contraseñas).
  - Secretos de GitHub Actions: keystore en base64, alias y contraseñas.
  - **Crítico**: si se pierde la clave, los usuarios de Obtainium/IzzyOnDroid no
    pueden actualizar sin desinstalar (se pierden sus hosts guardados).
  - Añadir `*.jks`/`*.keystore` a `.gitignore`.
- `signingConfigs.release` leído de variables de entorno y propiedades de Gradle
  (nunca en claro en el repositorio); sin ellas, la release sale sin firmar o
  falla, pero la build de debug sigue funcionando.
- `buildTypes.release`: decidir `isMinifyEnabled` (R8) y `isShrinkResources`. Si
  se activa, reglas de keep para sshj, BouncyCastle (proveedores JCE por
  reflexión), kotlinx.serialization y los recursos del agente (`/agent/`).
- `dependenciesInfo { includeInApk = false; includeInBundle = false }`: el bloque
  cifrado que añade AGP es opaco y IzzyOnDroid y F-Droid piden quitarlo.
- Artefactos: **APK universal** (no hay librerías nativas propias; confirmarlo)
  para GitHub Releases, y **AAB** para Play.
- Revisar los permisos del manifiesto (INTERNET y cualquier otro), porque
  determinan la ficha de Seguridad de los datos de Play y la revisión de
  IzzyOnDroid.
- Tamaño de la APK con los binarios del agente
  ([[ADR-0010 Empaquetado del agente y descarga bajo demanda]] y
  [[Verificar empaquetado del agente en APK Android]]) anotado aquí.
- Opcional: builds reproducibles (IzzyOnDroid muestra una insignia de
  reproducible).

## Verificación

<Se rellena al completar: instalar la APK de release en el Pixel ([[ssh-test-host]]), conectar con clave en hardware y nivel 3, y actualizar sobre ella una segunda versión firmada con la misma clave.>

## Resultado

<Se rellena al completar.>
