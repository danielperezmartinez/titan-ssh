---
Nombre: "Gestión de claves y secretos (UI)"
Estado: En curso
Resumen: 'Aprovisionar material de autenticación desde la app para que los métodos de auth del host sean usables de punta a punta. Falta la UI que genera una clave hardware no exportable (y muestra su línea authorized_keys para enrolar), importa/genera una clave software y guarda contraseñas/passphrases en el SecretStore. Hoy el editor de host solo REFERENCIA material que nada crea; sin esto no se puede probar una conexión real desde la app.'
Decisiones: Consume [[ADR-0001 Credenciales en almacén nativo del SO]] y [[ADR-0005 Autenticación SSH y verificación de host]]. Se apoya en la fundación de [[Almacenamiento seguro de credenciales]] y el signer de [[Autenticación SSH signer en hardware y verificación de host]].
Bloqueada: []
Fecha de creación: 2026-09-18T16:10:00+02:00
Última modificación: 2026-09-18T17:05:00+02:00
---

# Gestión de claves y secretos (UI)

## Objetivo

Cerrar el hueco de aprovisionamiento: que el usuario pueda **crear/guardar** el
material de autenticación desde la app, no solo referenciarlo. Hoy el editor de
host ([[Panel de gestión de hosts y sesiones]]) solo acepta un *nombre de
referencia* (SecretStore) o un *alias* del almacén del SO, pero **nada crea ese
material**, así que ningún método de auth se completa de punta a punta y no se
puede probar una conexión real con el [[Terminal multipestaña con sesiones simultáneas]].

La capacidad de bajo nivel ya existe: `AndroidHardwareKeys.ensureKey(alias)`
genera la clave EC P-256 no exportable y expone su línea `authorized_keys`
(verificado en dispositivo por la pantalla de depuración
`HardwareSignerTestScreen`, que no está cableada en la UI de producto). Esta tarea
lleva esa capacidad a la UI real y añade el aprovisionamiento de secretos software.

## Criterios de finalización

- **Clave hardware (objetivo v1, Android)**: desde la sección "Clave hardware" del
  editor de host, una acción **Generar / mostrar clave** que:
  - genere (o reutilice) la clave no exportable en el almacén del SO **en el alias
    elegido** (no el alias fijo del banco de pruebas), vía un seam `expect`/`actual`
    para poder invocarla desde `commonMain` (androidMain sobre `AndroidHardwareKeys`;
    en escritorio no hay clave hardware no exportable en v1 → acción no disponible).
  - muestre y permita **copiar la línea `authorized_keys`** para enrolarla en el
    servidor.
- **Contraseña**: un campo para introducir la contraseña y **guardarla en el
  SecretStore** bajo la referencia que usa el host (hoy la referencia no resuelve
  porque nada la escribe).
- **Clave software**: **importar** una clave privada existente (PEM) —y opcional:
  **generar** un par ed25519— guardando la privada en el SecretStore bajo su
  referencia y mostrando la línea pública para enrolar. La passphrase, si la hay,
  también al SecretStore.
- **Sin texto plano**: todo el material sensible va al `SecretStore`; el documento
  de config sigue guardando solo referencias/alias (ADR-0001). La clave hardware
  nunca sale del almacén.
- La UI respeta el lenguaje visual dark-first (ver [[Componentes UI compartidos]] /
  [[Vocabulario ASCII ampliado y disciplina de color]]).

## Verificación

**Headless (hecho):**

- Build de los cuatro targets → `BUILD SUCCESSFUL` (`GRADLE_EXIT=0`, `JAVA_HOME`
  al JBR, wrapper, `--console=plain`): `:shared:compileKotlinDesktop`,
  `:shared:compileAndroidMain`, `:androidApp:compileDebugKotlin`,
  `:desktopApp:compileKotlin`. APK: `:androidApp:assembleDebug` → `BUILD
  SUCCESSFUL` (APK dejado en `androidApp/build/outputs/apk/debug/androidApp-debug.apk`
  para que el usuario lo pruebe).
- `:shared:desktopTest` sin fallos (66 tests; los de integración se saltan sin
  credenciales). Test nuevo `SecretProvisionerTest` (8): guarda contraseña UTF-8,
  rechaza contraseña vacía, importa clave PEM con y sin passphrase, rechaza texto
  no-PEM, rechaza passphrase sin referencia, `has`/`remove`, y un round-trip de
  punta a punta escritura (`SecretProvisioner`) → lectura (`CredentialResolver`)
  que prueba que lo aprovisionado se materializa igual al conectar. Fuente:
  `shared/src/desktopTest/kotlin/im/gar/titanssh/secret/SecretProvisionerTest.kt`.
- Warning conocido y benigno: deprecación de `LocalClipboardManager` (mismo que ya
  usa `TerminalView`; migrar a `LocalClipboard` es trabajo futuro).

**Pendiente de comprobación real del usuario (por eso queda `En curso`, mismo
criterio que el terminal):**

- **Clave hardware en el pixel-9-pro-xl**: generar la clave desde el editor de host
  con el alias elegido, copiar la línea `authorized_keys`, enrolarla en el host de
  pruebas y **conectar desde el terminal** autenticando con la firma del Keystore.
  La capacidad de bajo nivel ya está verificada en dispositivo (ver
  [[Autenticación SSH signer en hardware y verificación de host]]); falta ejercerla
  por la UI de producto.
- **Contraseña / clave software**: guardar el secreto desde el editor y conectar
  una sesión que lo referencie, en escritorio (`:desktopApp:run`) y/o Android.
- No puedo arrancar la ventana de escritorio ni un dispositivo ni conectar a un
  host real con credenciales; queda a la comprobación del usuario.

## Resultado

Aprovisionamiento de material de autenticación cerrado en la UI de producto
(paquetes `im.gar.titanssh.ssh` y `im.gar.titanssh.secret` + editor de host):

- **Seam de clave hardware** `expect`/`actual` en `commonMain`
  (`HardwareKeyProvisioning.kt`): `isHardwareKeyProvisioningSupported()` +
  `ensureHardwareKey(alias)`; `actual` de Android sobre `AndroidHardwareKeys`
  (genera/reutiliza la clave EC P-256 no exportable **en el alias elegido**, off
  main thread) y `actual` de escritorio que no lo soporta (v1). Ficha de catálogo
  [[Aprovisionamiento de clave hardware]].
- **`SecretProvisioner`** (`commonMain`): lado de escritura del `SecretStore`:
  `savePassword`, `importPrivateKey` (PEM + passphrase opcional, con validación),
  `has`/`remove`; borra la copia transitoria tras escribir. Contraparte del
  `CredentialResolver`. Ficha de catálogo [[SecretProvisioner]].
- **Editor de host** (`HostEditor.kt`): la sección Autenticación ahora **crea** el
  material, no solo lo referencia. Clave hardware: acción **[>] Generar / mostrar
  clave** que genera bajo el alias escrito, muestra la línea `authorized_keys` en
  un `SelectionContainer` y permite copiarla; deshabilitada con nota en escritorio.
  Contraseña: campo + **guardar en el almacén** bajo la referencia del host. Clave
  software: campo PEM (+ passphrase) + **importar al almacén** bajo sus
  referencias. Feedback de éxito/error con disciplina de color (success/danger para
  estado real). Cableado: `AppShell` crea un único `SecretStore` compartido por el
  `SessionManager` (lectura) y un `SecretProvisioner` (escritura), inyectado por
  `ConfigArea` → `HostEditor`.
- **Sin texto plano**: todo el material va al `SecretStore`; el documento de config
  sigue guardando solo referencias/alias (ADR-0001). La clave hardware nunca sale
  del almacén del SO.
- **Stretch no hecho** (documentado): generar un par ed25519 in-app. `eddsa` está
  en el classpath de `jvmShared`, pero serializar a mano el PEM OpenSSH de la
  privada es frágil; se deja para una iteración futura. La importación de una clave
  existente cubre el caso software de la v1.
- La pantalla de depuración `HardwareSignerTestScreen` (alias fijo) se conserva en
  el árbol como banco de pruebas; la funcionalidad de producto vive en el editor.
