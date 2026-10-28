---
Nombre: 'Clave no exportable en el TPM del escritorio'
Número: 33
Estado: 'Aceptada'
Resumen: 'La clave hardware de la app de escritorio es una EC P-256 en el TPM 2.0 del equipo, como la de Android en el Keystore. En Windows, por CNG con el Microsoft Platform Crypto Provider (JNA). En Linux, con un cliente TPM 2.0 propio sobre /dev/tpmrm0, sin paquetes extra; el usuario tiene que estar en el grupo tss. sshj firma con ella a través de un KeyAlgorithm envoltorio, sin tocar la JCE global. Las llaves de seguridad (FIDO/YubiKey) y el agente SSH del sistema quedan para el futuro.'
Decisión: 'En escritorio la clave no exportable vive en el TPM 2.0 (Windows: CNG Platform Crypto Provider; Linux: /dev/tpmrm0 con un cliente propio) y sshj firma con ella mediante PlatformSigningKey.'
Consecuencias: 'Sin un TPM 2.0 usable la acción sigue desactivada, ahora con el motivo (no hay TPM, o en Linux el usuario no está en el grupo tss). En Linux el blob cifrado por el TPM se guarda en la carpeta de configuración y solo sirve en ese TPM. Las claves de la estable y de la canary van aparte. Un Flatpak tendrá que pedir acceso a /dev/tpmrm0. Las claves solo admiten P-256 (ningún TPM hace ed25519).'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-28T18:30:00+01:00
Última modificación: 2026-10-28T18:30:00+01:00
---

# ADR-0033 · Clave no exportable en el TPM del escritorio

## Contexto

[[ADR-0005 Autenticación SSH y verificación de host]] fija como objetivo una
clave privada no exportable que firma en hardware. Hasta ahora solo Android la
tenía (Keystore/StrongBox, [[Autenticación SSH signer en hardware y verificación de host]]).
En escritorio el editor decía «no disponible». La tarea
[[Clave en hardware en la app de escritorio]] la pide para Windows y Linux.

Esta ADR no cambia la decisión de ADR-0005, sino que la completa: dice qué almacén
se usa en escritorio y cómo se conecta con sshj.

sshj firma pidiendo un `java.security.Signature` a `SecurityUtils`, que en
escritorio fuerza el proveedor BouncyCastle. BC no puede firmar con un handle
de un almacén externo, igual que en Android, donde la salida fue no forzar BC
para que la JCE enrutase la clave al proveedor AndroidKeyStore. En escritorio
no hay ningún proveedor JCE de TPM al que enrutar.

## Decisión

- **Almacén: el TPM 2.0 del equipo, clave EC P-256** (`ecdsa-sha2-nistp256`),
  el mismo tipo que en Android. Los TPM no implementan ed25519.
- **Windows: CNG con el *Microsoft Platform Crypto Provider***
  (`NCryptCreatePersistedKey`, `NCryptExportKey`, `NCryptSignHash`) a través
  de JNA, que ya llegaba con java-keyring. Windows guarda la clave por usuario
  y no permite exportarla. Los nombres son `<app>/<alias>`, así que la estable
  y la canary tienen claves distintas, como en Android.
- **Linux: un cliente TPM 2.0 propio sobre `/dev/tpmrm0`** (el gestor de
  recursos del kernel), con los pocos comandos necesarios: `CreatePrimary` con
  la plantilla estándar del SRK de TCG, `Create`, `Load`, `Sign` y
  `FlushContext`. El TPM devuelve la clave cifrada con su SRK. Ese blob se
  guarda en `hardware-keys/` dentro de la carpeta de configuración de la app
  (solo para el propietario) y solo sirve en ese TPM. Si la jerarquía *owner*
  tiene contraseña, se usa el SRK persistente `0x81000001`.
  - La clave no lleva contraseña propia (como en Android y en Windows sin PIN)
    y lleva `noDA`. No hay nada que adivinar, y un bloqueo por ataque de
    diccionario bloquearía también las otras claves del TPM del usuario (p. ej.
    un disco que se desbloquea con PIN).
  - **Requisito:** el usuario tiene que poder leer y escribir `/dev/tpmrm0`.
    En casi todas las distribuciones eso significa pertenecer al grupo `tss`
    (`sudo usermod -aG tss $USER` y volver a iniciar sesión). La app lo
    detecta y lo explica en el editor.
- **Integración con sshj:** la clave privada es un `PlatformSigningKey` (un
  `PrivateKey` con `sign(data)`). `sshjConfig` envuelve los `KeyAlgorithm`
  (`withPlatformSigning`): si la clave es de plataforma, la firma la hace la
  plataforma; si no, todo va a la firma de sshj como antes. El resto
  (`HardwareKeyRegistry`, `DelegatedKeyProvider`, el alias en
  `HostAuth.HardwareKey`, el editor y la instalación en el host) es el mismo
  que en Android.
- `hardwareKeyUnavailableReason()` sustituye a
  `isHardwareKeyProvisioningSupported()`: además de decir si se puede, dice por
  qué no.

## Alternativas consideradas

- **tpm2-pkcs11 + SunPKCS11 (Linux):** hay que instalar un paquete e
  inicializar un token con PIN, y la JCE no expone la creación del token. Es
  más frágil que hablar con el TPM directamente y no aporta nada.
- **tpm2-tss (ESAPI) por JNA:** una dependencia nativa más en cada
  distribución, para cinco comandos.
- **Comandos TPM por TBS también en Windows:** Windows se queda la contraseña
  de *owner*, así que habría que depender del SRK persistente de Windows. El
  Platform Crypto Provider es la vía oficial y ya resuelve el SRK y el
  almacenamiento.
- **Agente SSH del sistema** (ssh-agent, el agente de OpenSSH de Windows,
  Pageant, KeePassXC, ssh-tpm-agent): da mucha flexibilidad, pero la clave no
  la genera la app. Queda como complemento futuro en
  [[Llaves de seguridad y agente SSH en el escritorio]].
- **Llaves FIDO2 `sk-*` (YubiKey):** sshj no implementa esos tipos de clave.
  Habría que hacer CTAP2 por HID y, en Windows, pasar por `webauthn.dll`, y
  además hace falta una llave física. También queda para esa tarea.

## Consecuencias

- Positivas: la clave del escritorio nunca sale del TPM; en Linux no hace
  falta ningún paquete; la JCE global no cambia y las claves software firman
  igual que antes.
- Negativas / compromisos: en Linux hay que estar en el grupo `tss`. La clave
  queda atada a ese equipo: un TPM borrado o cambiado la pierde, y entonces se
  genera otra y se instala de nuevo en el host. Cada firma en Linux recrea el
  SRK (una fracción de segundo por conexión). Cuando exista el Flatpak
  ([[Canal Linux Flatpak en Flathub]]), tendrá que pedir acceso a
  `/dev/tpmrm0` (`--device=all`).
