---
Nombre: 'Instalar la clave pública en el host desde la app'
Estado: 'Hecha'
Resumen: 'Casilla en el editor de host que genera la clave, pide la contraseña una sola vez, copia la línea pública en authorized_keys del servidor (como ssh-copy-id, también en Windows con administrators_authorized_keys), comprueba que la clave entra y solo entonces cambia el host a autenticación por clave. Texto previo que explica cada paso y catálogo cerrado de errores. Incluye generar pares ed25519 por software (escritorio y Android) y keyboard-interactive en el motor. Primero solo en la canary.'
Decisiones: 'Sigue [[ADR-0030 Instalación de la clave pública en el host con una contraseña de un solo uso]]. Acordado con el usuario el 2026-10-21: solo canary al principio; ed25519 por software en escritorio; la casilla va en el editor de host; si el host tenía contraseña guardada, el resumen ofrece borrarla (no se borra sola).'
Bloqueada: []
Versión: '1.5.0'
Fecha de creación: 2026-10-21T18:00:00+02:00
Última modificación: 2026-10-29T00:30:00+01:00
---

# Instalar la clave pública en el host desde la app

## Objetivo

Hoy, para entrar con clave, el usuario genera la clave en la app y tiene que
llevar la pública al servidor por su cuenta. Esta tarea lo automatiza con
transparencia total, según
[[ADR-0030 Instalación de la clave pública en el host con una contraseña de un solo uso]].
Continúa el pendiente de generar pares por software de
[[Gestión de claves y secretos UI]].

## Flujo

1. Editor de host, sección Autenticación: casilla «Instalar la clave en el
   host por mí» junto a la acción de generar clave. Con la casilla, la acción
   pasa a «Generar e instalar».
2. Genera la clave (o reutiliza la del alias / la referencia).
3. Diálogo de contraseña de `<usuario>@<host>`; solo en memoria.
4. Conecta con `password` (o `keyboard-interactive`), verificación de host
   habitual y saltos del host.
5. Detecta Unix o Windows (como `AgentInstaller`).
6. Escribe la línea (script POSIX por `exec` en Unix, SFTP de respaldo; SFTP e
   `icacls` en Windows).
7. Conexión nueva solo con la clave.
8. Si entra, guarda el host con el método de clave y enseña el resumen
   (✓/✗ por paso, y «Borrar la contraseña guardada» si la había).

## Texto al marcar la casilla

> Al pulsar «Generar e instalar», titan-ssh:
> 1. Genera una clave nueva en este dispositivo (en Android, dentro del chip
>    de seguridad; no sale de él).
> 2. Te pide la contraseña de `<usuario>@<host>` una sola vez. No se guarda:
>    se usa para esta conexión y se borra de la memoria.
> 3. Se conecta con esa contraseña y comprueba la identidad del servidor como
>    siempre (te preguntará si es la primera vez).
> 4. Añade la línea pública de la clave a `~/.ssh/authorized_keys` del host
>    (en Windows, al fichero que use su OpenSSH). Crea la carpeta si no existe,
>    ajusta sus permisos y no la duplica si ya estaba. No toca nada más.
> 5. Vuelve a conectar solo con la clave para comprobar que funciona.
> 6. Si todo va bien, este host pasa a entrar con la clave. Si algo falla, no
>    cambia nada aquí y te dice qué ha pasado.
>
> Se guarda: la clave (en el almacén del sistema) y la huella del servidor
> (known_hosts). La contraseña, nunca.

## Qué se guarda

| Dato | Dónde |
|---|---|
| Clave hardware (Android) | Keystore / StrongBox, por alias; nunca sale |
| Clave ed25519 por software | `SecretStore`, bajo su referencia (ADR-0001) |
| Configuración del host | `HostAuth.*Key` con alias o referencia, sin material |
| Clave del servidor | known_hosts, como cualquier conexión |
| Contraseña | En ningún sitio: `CharArray` borrado al terminar |

## Catálogo de errores

**Conexión y confianza**

1. No se llega al host (DNS, conexión rechazada, tiempo agotado).
2. Clave de host desconocida rechazada por el usuario: se cancela; la
   contraseña no se ha enviado.
3. Clave de host cambiada: bloqueada como posible MITM; no se envía nada.
4. Política estricta y host desconocido.
5. Salto: falla la autenticación del bastión (se nombra, con sus credenciales)
   o el bastión no llega al destino.

**Autenticación con contraseña**

6. El servidor no ofrece `password` ni `keyboard-interactive` (se listan los
   métodos que ofrece y cómo seguir).
7. Usuario o contraseña incorrectos; se puede reintentar.
8. Segundo factor (éxito parcial, o preguntas de `keyboard-interactive` que no
   son la contraseña): no admitido en esta versión.
9. Contraseña caducada: el servidor pide cambiarla.
10. Demasiados intentos (`MaxAuthTries`): el servidor corta.

**Escritura en el servidor**

11. La cuenta no admite órdenes (`ForceCommand`, solo `internal-sftp`, shell
    restringida): se intenta por SFTP y, si tampoco, se explica.
12. `~/.ssh` existe y es un fichero, o pertenece a otro usuario.
13. Home con escritura para grupo u otros: `StrictModes` ignoraría la clave;
    no se toca y se indica `chmod go-w ~`.
14. `authorized_keys` no escribible (inmutable, enlace, gestionado por otra
    herramienta).
15. Home de solo lectura (EROFS).
16. Disco lleno o cuota (ENOSPC / EDQUOT).
17. La clave ya estaba: no es un error; «ya estaba» y se verifica igual.
18. Windows administrador sin permiso en `%ProgramData%\ssh`, o fallo de
    `icacls`.
19. Sistema no reconocido (equipos de red, servidores sin shell ni SFTP).
20. Corte a mitad: estado incierto; reintentar es seguro (idempotente).
21. Fallo de la clave hardware (Keystore / StrongBox).
22. Cancelación del usuario.

**Verificación final (copiada pero no entra)**

23. El servidor no ofrece `publickey` (`PubkeyAuthentication no`).
24. Tipo de clave no aceptado (`PubkeyAcceptedAlgorithms`, servidor antiguo).
25. `AuthorizedKeysFile` o `AuthorizedKeysCommand` propios (LDAP, ruta
    central).
26. `StrictModes` por permisos no detectados. Cada causa probable con cómo
    comprobarla.

**Aviso**

27. Una sesión con usuario propio no usa la clave instalada para el usuario
    del host.

## Criterios de finalización

- Casilla, texto, diálogo de contraseña y resumen en el editor de host, solo
  en la canary.
- Generación de ed25519 por software con la privada en el `SecretStore`.
- `keyboard-interactive` en el motor.
- Instalación idempotente en Unix y Windows; el host solo cambia tras la
  verificación con la clave; la contraseña nunca se persiste.
- Cada entrada del catálogo con su mensaje y, las que se puedan reproducir, con
  test (contenedores desechables en otros puertos, no `titan-test-sshd`).
- Prueba en el emulador con build de debug contra `titan-test-sshd`.

## Verificación

**Tests (escritorio, `:shared:desktopTest`, 525 sin fallos):**

- `KeyInstallScriptsTest` (11): el script de Unix corre con el `/bin/sh` de
  la máquina sobre una home temporal: crea `~/.ssh` 700 y `authorized_keys`
  600, no duplica una clave ya puesta (aunque cambie el comentario), añade el
  salto de línea que falta, corrige permisos, avisa de una home con escritura
  para el grupo sin tocarla, `~/.ssh` que es un fichero, fichero sin permiso de
  escritura, home inexistente, comillas en la línea; y la lectura de
  respuestas, el `authorized_keys` por SFTP y los ayudantes de Windows.
- `SoftwareKeysTest` (5): par ed25519 en formato OpenSSH que sshj vuelve a
  leer a la misma pública; identidad de una línea con opciones y comentario.
- `KeyInstallerTest` (13): servidores falsos para el flujo, la contraseña
  copiada y borrada, clave de host nueva / rechazada / cambiada / STRICT, cada
  rechazo de contraseña, saltos, la comprobación final, cuentas sin shell por
  SFTP y Windows (usuario, administrador con `icacls`, `sshd_config` sin la
  regla, fallos de escritura y de ACL).
- `KeyInstallIntegrationTest` (7, opt-in) contra sshd reales
  (`tools/test-sshd/key-install-scenarios.sh`): instala y no duplica, entra
  solo con `keyboard-interactive` (PAM), pasa por un salto, y da el error
  correcto con contraseña incorrecta, contraseña desactivada, clave de host
  rechazada y cambiada, home de solo lectura, disco lleno, `~/.ssh` de root,
  home con escritura para el grupo (StrictModes), `PubkeyAuthentication no` y
  `AuthorizedKeysFile` fuera de la home.

**Emulador** (`Pixel_9_Pro_XL`, build de debug canary, 2026-10-21): host
`demo-docker` contra `titan-test-sshd` que entraba con contraseña guardada;
se pasó a clave hardware con la casilla, se aceptó la clave del servidor en
el propio flujo, los seis pasos salieron `[ok]`, se borró la contraseña
guardada desde el resumen y una sesión nueva del host entró con la clave. Con
un contenedor sin acceso por contraseña (clave software), el error «El
servidor no acepta contraseña» con los métodos que ofrece y «La contraseña no
ha llegado a enviarse».

**Sin probar contra un servidor real:** Windows (no hay destino Windows en
este equipo; cubierto con servidores falsos) y el segundo factor (cubierto con
servidores falsos). La interfaz de escritorio comparte el código de la de
Android y no se ha mirado aparte.

## Resultado

- **Motor** (`SshjConnector`, `SshjPasswordAuth.kt`): con contraseña prueba
  `password` y después `keyboard-interactive`, respondiendo solo a una
  pregunta de contraseña; `SshAuthFailed` lleva el host que rechazó, los
  métodos que ofrecía, el éxito parcial, las preguntas sin responder y la
  petición de cambiar la contraseña. Beneficia también a las sesiones normales.
- **Claves por software** (`SoftwareKeys.kt`): `generateEd25519KeyPair`,
  `publicLineOfPrivateKey` y `SecretProvisioner.ensureSoftwareKey`, que genera
  en una referencia vacía y reutiliza la que ya tenga clave.
- **Instalador** (`keyinstall/`): `KeyInstaller`, el catálogo
  `KeyInstallProblem` y `KeyInstallScripts`. Ficha
  [[Instalador de clave en el host]].
- **Interfaz**: `KeyInstallPanel` en las secciones de clave software y
  hardware del editor de host, solo en la canary (`keyInstallOffered`): la
  casilla, el texto de los seis pasos, la contraseña de un solo uso, los pasos
  en vivo, la pregunta de clave de host y el resumen con el botón para borrar
  la contraseña que el host tenía guardada. El host se guarda con la clave
  solo tras la comprobación.
- **Pruebas**: `tools/test-sshd/key-install-scenarios.sh` crea los
  contenedores de cada caso (puertos 2240-2249).
