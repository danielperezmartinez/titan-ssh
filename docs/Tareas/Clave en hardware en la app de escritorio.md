---
Nombre: 'Clave en hardware en la app de escritorio'
Estado: 'Por probar'
Estado desde: 2026-10-28
Resumen: 'La app de escritorio puede autenticarse con una clave EC P-256 no exportable en el TPM 2.0 del equipo, como Android con el Keystore. En Windows va por CNG (Microsoft Platform Crypto Provider) y en Linux por un cliente TPM 2.0 propio sobre /dev/tpmrm0, para lo que el usuario tiene que estar en el grupo tss. El editor genera la clave, enseña su línea pública y la instala en el host; si no hay TPM usable, dice por qué. Verificado con swtpm en Linux y con el TPM de la VM de Windows contra un sshd real. Falta la prueba del usuario en un Windows y un Linux reales.'
Decisiones: 'Sigue [[ADR-0005 Autenticación SSH y verificación de host]] y la completa [[ADR-0033 Clave no exportable en el TPM del escritorio]] (TPM en las dos plataformas, elegida por el usuario el 2026-10-28). Las llaves de seguridad y el agente SSH quedan en [[Llaves de seguridad y agente SSH en el escritorio]]. La firma delegada de Android está en [[Autenticación SSH signer en hardware y verificación de host]]. Documentación de usuario en la página «Claves en hardware» del sitio (site/hardware-keys.html).'
Bloqueada: []
Prueba: 'Generar una clave hardware en el editor de host de la canary, instalarla en un host y conectar, en un Windows y en un Linux reales (en Linux, tras entrar en el grupo tss)'
Esperando a: ''
Versión: ''
Fecha de creación: 2026-10-28T17:30:00+01:00
Última modificación: 2026-10-28T19:40:00+01:00
---

# Clave en hardware en la app de escritorio

## Objetivo

Que en la app de escritorio se pueda conectar con una clave privada que no
sale del hardware del equipo, igual que en Android, en lugar del aviso «aún no
disponible» de antes (en escritorio, `HostEditor` no ofrecía la acción).

## Criterios de finalización

- ✅ Vía elegida para Windows y para Linux:
  [[ADR-0033 Clave no exportable en el TPM del escritorio]].
- ✅ Generar la clave, enseñar su línea pública, instalarla en el host desde
  la app (como en [[Instalar la clave pública en el host desde la app]]) y
  conectar firmando en hardware.
- ⏳ Probado por el usuario en un Windows y un Linux reales.

## Verificación

- **Tests sin TPM:** `PlatformSigningTest`. Una clave de plataforma firma a
  través de la config de sshj y la firma la verifica el `Signature` de sshj del
  lado del servidor; las claves normales siguen firmando como antes; el paso de
  r‖s a DER verifica en la JCE (64 firmas, incluidas las que necesitan el cero
  inicial).
- **Linux contra swtpm** (`LinuxTpmKeysTest`, se salta si swtpm no está
  instalado): crea la clave una vez y la vuelve a encontrar (también desde otra
  instancia); el fichero queda `rw-------`; la firma del TPM verifica con la
  clave pública que da; los alias se convierten en nombres de fichero seguros;
  el motivo cuando no hay `/dev/tpmrm0` o no hay acceso; los códigos de error
  de autorización.
- **Linux de punta a punta** (swtpm + un `titan-test-sshd` desechable en otro
  puerto): `KeyInstaller` instala la clave con la contraseña, comprueba que
  entra, y después una conexión solo con la clave TPM entra. sshd registra dos
  `Accepted publickey … ECDSA`.
- **Windows en la VM de pruebas** (Windows 11, TPM 2.0 emulado por swtpm, JDK
  de los paquetes de escritorio): `WindowsTpmKeysTest` y `PlatformSigningTest`
  ejecutados en el escritorio del usuario (5/5). La clave se crea en el
  Platform Crypto Provider, se reutiliza y firma, y la misma prueba de punta a
  punta entra en un sshd Linux desechable con la clave del TPM de Windows.
  - Por una sesión SSH (logon S4U, sin credenciales), `NCryptFinalizeKey` da
    `NTE_PERM`. No afecta a la app, que corre en el escritorio del usuario.
    Para repetir la prueba por SSH hay que lanzarla en el escritorio (tarea
    programada interactiva).
  - Instalar la clave en la propia VM como destino Windows falló en la
    escritura por SFTP. Es un fallo del instalador en destinos Windows, ajeno
    al TPM, y queda en [[Instalar la clave en un destino Windows real]].
- **Suite completa:** `:shared:desktopTest` (537 tests, 0 fallos),
  `:desktopApp:compileKotlin` y `:androidApp:assembleDebug`.
- Sin probar: un TPM físico (este PC no tiene). Lo cubre la prueba del
  usuario.

## Cómo probarlo

**Dónde:** titan-ssh canary de escritorio desde el commit que trae este
cambio (`edecc1c`, «Sign with a TPM key on the desktop app») o posterior, en
un **Windows 10/11 real** y en un **Linux real con TPM 2.0**. Destino: un
servidor SSH Linux al que se entre con contraseña (p. ej. `<host-de-pruebas>`
o un contenedor de `tools/test-sshd/`).

**Preparación:**

- Windows: abrir `tpm.msc` y confirmar «El TPM está listo para usarse».
- Linux: `ls -l /dev/tpmrm0` (debe existir). Si tu usuario no está en el
  grupo del fichero (normalmente `tss`): `sudo usermod -aG tss $USER`, cerrar
  la sesión y volver a entrar.

**Pasos** (en cada equipo):

1. (Solo Linux, opcional) Antes de entrar en el grupo `tss`, abrir el editor
   de un host y elegir «Clave (hardware)»: debe salir en amarillo «No
   disponible: Tu usuario no tiene acceso al TPM (/dev/tpmrm0). Añádelo al
   grupo tss…», con el botón desactivado.
2. Nuevo host (o editar uno) → Autenticación → «Clave (hardware)», con un alias
   p. ej. `tpm-prueba`.
3. Marcar «Instalar la clave en el host por mí» y escribir la contraseña del
   servidor.
4. Debe terminar con la clave instalada y el host guardado con la clave
   hardware. En el servidor, `~/.ssh/authorized_keys` tiene una línea
   `ecdsa-sha2-nistp256 …`.
5. Abrir una sesión de ese host: debe conectar sin pedir contraseña.
6. Cerrar la app, volver a abrirla y conectar otra vez: entra con la misma
   clave (en «Generar / mostrar clave» aparece la misma línea).

**Qué debe pasar:** los pasos 4 a 6 sin errores. Con otro alias se crea otra
clave distinta.

**Si falla:** enviar el texto del error del editor o de la franja de la
pestaña, el sistema y versión (`winver`, o la distribución y `uname -r`), en
Linux la salida de `ls -l /dev/tpmrm0` e `id`, y en Windows una captura de
`tpm.msc`.

## Resultado

- **Firma de plataforma en el motor** (`HardwareSigner.kt`, `SshjConfig.kt`):
  `PlatformSigningKey` y `withPlatformSigning`, que envuelve los
  `KeyAlgorithm` de sshj; `ecdsaSignatureDer`.
- **Windows** (`WindowsTpmKeys.kt`): CNG/NCrypt por JNA, con las claves
  `<app>/<alias>` en el Microsoft Platform Crypto Provider.
- **Linux** (`Tpm2.kt`, `LinuxTpmKeys.kt`): cliente TPM 2.0 sobre
  `/dev/tpmrm0` y blobs `hardware-keys/<alias>.tpmkey` en la carpeta de
  configuración (solo para el propietario).
- **Entrada común** (`HardwareKeyProvisioning.desktop.kt`):
  `DesktopHardwareKeys`, que `main` instala al arrancar;
  `hardwareKeyUnavailableReason()` sustituye a
  `isHardwareKeyProvisioningSupported()`, y el editor enseña el motivo.
- JNA pasa a ser dependencia directa de `desktopMain` (la misma 5.13.0 que
  traía java-keyring).
- Documentación de usuario: `site/hardware-keys.html` (español e inglés),
  enlazada desde la página del escritorio remoto. Cuando llegue a la estable,
  quitar el aviso de «solo en la canary» de esa página y ampliar la función
  «Claves y contraseñas» de la portada (`site/index.html` y `site/app.js`).
