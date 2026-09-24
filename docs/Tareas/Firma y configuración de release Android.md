---
Nombre: 'Firma y configuración de release Android'
Estado: 'En curso'
Resumen: 'Preparar la build de release de Android. Crear una clave de firma propia (keystore) que nunca va al repositorio, vive en los secretos de CI y tiene copia de seguridad del usuario, y que servirá también como clave de subida si algún día se usa Play. Añadir signingConfig leído de variables de entorno y el buildType release, decidir R8 (con reglas para sshj/BouncyCastle), desactivar el bloque de dependencias que añade AGP (IzzyOnDroid y F-Droid lo piden) y generar APK universal y AAB. Verificar la APK de release en el Pixel. Estado a 2026-09-24: configuración hecha y verificada en el emulador con una clave desechable; falta que el usuario cree el keystore real y la prueba en el Pixel.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §5–6.'
Bloqueada:
  - "[[Icono y recursos gráficos de la app]]"
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-24T14:20:00+02:00
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

Hecha el 2026-09-24 en la rama `fase2-release`, salvo la clave real (ver
**Pendiente**). La APK de release se firmó con una clave **desechable** de
prueba (validez de 1 día, borrada después) y se probó en el emulador
`Pixel_9_Pro_XL` (Android 15):

- Sin variables de firma, `assembleRelease` genera
  `androidApp-release-unsigned.apk`, y la build de debug no cambia.
- Con ellas, `assembleRelease` genera `androidApp-release.apk` firmada, y
  `bundleRelease` el `.aab`.
- La APK arranca con R8 activo. La pantalla Acerca de muestra
  `0.1.0-beta.1` y los textos legales.
- Conexión con **clave hardware** (ECDSA P-256 del Keystore) contra un sshd
  temporal en Docker (`10.0.2.2:2222` desde el emulador). La huella TOFU se
  comprobó contra la clave de host del contenedor. Se conecta en **nivel 3**:
  instala `agent-0.1.0-beta.1-linux-amd64` y, al matar la sesión SSH en el
  servidor, la app reconecta sola con el mismo daemon y el marcador escrito
  antes del corte sigue en pantalla.
- **Actualización**: `0.1.0-beta.2`, firmada con la misma clave, se instala
  encima con `adb install -r` (`versionCode` 10031 → 10032) y conserva el host
  y la sesión guardados.

## Resultado

Cambios en `androidApp/build.gradle.kts` y `androidApp/proguard-rules.pro`,
nuevo:

- **Firma**: `signingConfigs.release` solo existe si llega el keystore. Cada
  valor se lee de una propiedad `-P` o, si falta, de una variable de entorno:

  | Propiedad `-P` | Variable de entorno |
  |---|---|
  | `titanKeystoreFile` | `TITAN_KEYSTORE_FILE` |
  | `titanKeystorePassword` | `TITAN_KEYSTORE_PASSWORD` |
  | `titanKeyAlias` | `TITAN_KEY_ALIAS` |
  | `titanKeyPassword` | `TITAN_KEY_PASSWORD` |

  Sin ellas, la release sale sin firmar. `*.jks` y `*.keystore` están en
  `.gitignore`.
- **Decisión: R8 activo** (`isMinifyEnabled` e `isShrinkResources`). Compose
  en Android necesita R8 para rendir bien. Se usa `-dontobfuscate` para que
  las trazas de los usuarios sean legibles (el código es público), y se
  conservan enteros BouncyCastle, eddsa y sshj, que resuelven algoritmos por
  nombre. kotlinx.serialization trae sus propias reglas.
- `dependenciesInfo { includeInApk = false; includeInBundle = false }`.
- **Tamaños** (`0.1.0-beta.1`): APK universal de **8,5 MB** (la de debug
  ocupa 18,7 MB) y AAB de 11,2 MB. Los tres binarios del agente suman unos
  7,5 MB sin comprimir y van dentro.
- **Librerías nativas**: la APK sí lleva una, `libandroidx.graphics.path.so`
  (de Compose), para `arm64-v8a`, `armeabi-v7a`, `x86` y `x86_64`, y pesa
  pocos KB. Se mantiene la APK **universal**, sin división por ABI.
- **Permisos**: solo `INTERNET`.
- Builds reproducibles: no se ha tratado. Queda para
  [[Canal Android IzzyOnDroid]].

**Pendiente para cerrar la tarea** (👤 el usuario):

1. Crear el keystore real. La contraseña la escribe el usuario, no pasa por el
   agente:
   `keytool -genkeypair -v -keystore titan-ssh-release.jks -alias titan-ssh -keyalg RSA -keysize 4096 -validity 10000`
   (unos 27 años), fuera del repositorio.
2. Guardar una copia de seguridad del `.jks` y de las contraseñas en el gestor
   de contraseñas.
3. Instalar en el Pixel una APK firmada con esa clave y repetir la conexión
   con clave hardware y nivel 3. Los secretos de GitHub (keystore en base64,
   alias y contraseñas) se cargan en el paso 6, con el pipeline.
