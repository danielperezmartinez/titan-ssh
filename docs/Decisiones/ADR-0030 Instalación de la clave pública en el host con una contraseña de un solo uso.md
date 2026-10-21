---
Nombre: 'Instalación de la clave pública en el host con una contraseña de un solo uso'
Número: 30
Estado: 'Propuesta'
Resumen: 'La app instala ella misma la clave pública de un host en el servidor, como ssh-copy-id: genera la clave (hardware no exportable en Android, ed25519 por software en el SecretStore en escritorio y como opción en Android), pide la contraseña del host una sola vez, conecta con ella (password o keyboard-interactive) con la verificación de host habitual y los saltos del host, añade la línea a authorized_keys sin duplicarla y con los permisos que exige StrictModes (en Windows, al fichero que use Win32-OpenSSH con sus ACL), y vuelve a conectar solo con la clave. El host pasa a autenticar con clave únicamente si esa verificación entra; si algo falla, no cambia nada y se muestra un error concreto de un catálogo cerrado. La contraseña nunca se guarda. Primero solo en la canary.'
Decisión: 'Añadir al editor de host una casilla que genera la clave, la instala en el servidor con una contraseña que solo vive en memoria durante la operación, verifica que la clave entra y solo entonces cambia la autenticación del host; nunca se persiste la contraseña.'
Consecuencias: 'Pasar de contraseña a clave deja de exigir herramientas de terceros o la sesión SFTP. La app escribe por primera vez en ficheros de configuración del usuario remoto (~/.ssh, authorized_keys, en Windows administrators_authorized_keys y sus ACL), así que la operación debe ser idempotente, tocar solo eso y explicarlo antes. El motor gana keyboard-interactive. Se añade la generación de pares ed25519 por software. Hay que mantener un catálogo de errores con su texto.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-21T18:00:00+02:00
Última modificación: 2026-10-21T18:00:00+02:00
---

# ADR-0030 · Instalación de la clave pública en el host con una contraseña de un solo uso

## Contexto

[[ADR-0005 Autenticación SSH y verificación de host]] prefiere la clave a la
contraseña, y el editor de host ya genera una clave hardware en Android
([[Aprovisionamiento de clave hardware]]). Pero la línea pública hay que
llevarla al servidor a mano: con `ssh-copy-id` desde otro equipo, copiándola y
pegándola, o con la sesión SFTP de la app
([[ADR-0024 Sesión SFTP con escritura, carpetas y selección múltiple]]). Es el
paso en que el usuario más se equivoca (permisos, fichero equivocado en
Windows, clave duplicada) y el que menos ve.

En escritorio, además, la app no genera ninguna clave: solo importa un PEM.

## Decisión

- **Dónde.** En la sección Autenticación del editor de host, que es donde vive
  la autenticación (afecta a todas las sesiones del host). Una casilla «Instalar
  la clave en el host por mí» junto a la acción de generar clave. Al marcarla,
  un texto explica todos los pasos, qué se guarda y qué no, antes de hacer nada.
- **Clave.** Android: la clave hardware no exportable de siempre (EC P-256).
  Escritorio, y como alternativa en Android: un par **ed25519 por software**
  generado en la app, con la privada en formato OpenSSH en el `SecretStore`
  ([[ADR-0001 Credenciales en almacén nativo del SO]]).
- **Contraseña de un solo uso.** Se pide en un diálogo en el momento, vive en
  un `CharArray` durante la operación y se borra al terminar, salga bien o mal.
  **Nunca se persiste.** Se usa con `password` y, si el servidor solo ofrece
  `keyboard-interactive`, respondiendo con ella a una pregunta de contraseña.
  Las preguntas que no son la contraseña (un segundo factor) no se responden en
  esta versión: se informa del error.
- **Confianza.** La conexión pasa por la verificación de host habitual (TOFU y
  política del host) y por los saltos configurados, cada uno con sus propias
  credenciales. Si el usuario rechaza la clave del servidor o esta ha cambiado,
  la contraseña no llega a enviarse.
- **Escritura en el servidor.** Solo se toca la carpeta de claves y el fichero
  de claves autorizadas, de forma idempotente:
  - Unix: un script POSIX por `exec` al estilo de `ssh-copy-id` (`umask 077`,
    `~/.ssh` 700, `authorized_keys` 600, sin duplicar la línea, `restorecon`
    si existe). No cambia los permisos de la home: si impiden la clave por
    `StrictModes`, lo dice. Si la cuenta no admite órdenes, se intenta por
    SFTP.
  - Windows (Win32-OpenSSH): el fichero se escribe por SFTP (nunca con
    redirecciones de PowerShell, que cambian la codificación). Un usuario
    administrador usa `%ProgramData%\ssh\administrators_authorized_keys`, con
    ACL solo para Administrators y SYSTEM, salvo que `sshd_config` no lo
    configure; el resto, `%USERPROFILE%\.ssh\authorized_keys`.
- **Verificación antes de cambiar nada.** Tras escribir, una conexión nueva
  solo con la clave. Únicamente si entra, el host pasa a `HostAuth.HardwareKey`
  o `HostAuth.SoftwareKey`. Si el host tenía una contraseña guardada, el
  resumen ofrece borrarla; no se borra sola.
- **Errores.** Cada fallo se traduce a un mensaje concreto de un catálogo
  cerrado (detalle en [[Instalar la clave pública en el host desde la app]]),
  nunca a una excepción genérica.
- **Canal.** Primero solo en la canary
  ([[ADR-0027 Canal canary instalable junto a la app estable]]); se abre en la
  estable cuando se haya probado en hosts reales.

## Alternativas consideradas

- **Guardar la contraseña para reintentar o reinstalar.** Descartada: el
  objetivo es dejar de depender de ella, y guardarla anula la ventaja.
- **Escribir siempre por SFTP también en Unix.** Descartada como camino
  principal: un leer-modificar-escribir del fichero entero puede pisar cambios
  concurrentes y no permite `restorecon`; queda como respaldo para cuentas sin
  shell.
- **Cambiar la autenticación del host en cuanto se copia la clave.** Descartada:
  si `AuthorizedKeysFile`, `PubkeyAcceptedAlgorithms` o los permisos impiden la
  clave, el host quedaría sin forma de entrar.
- **Corregir los permisos de la home o editar `sshd_config`.** Descartada: es
  configuración del sistema que el usuario no ha pedido cambiar; se explica qué
  hacer.

## Consecuencias

- Positivas: pasar de contraseña a clave es un solo gesto, transparente y
  comprobado; se cierra la generación de claves por software pendiente.
- Negativas / compromisos: la app escribe en el servidor, así que el script y
  el camino de Windows necesitan pruebas contra contenedores con cada caso de
  error; el motor gana `keyboard-interactive`; el catálogo de errores y sus
  textos hay que mantenerlos.
