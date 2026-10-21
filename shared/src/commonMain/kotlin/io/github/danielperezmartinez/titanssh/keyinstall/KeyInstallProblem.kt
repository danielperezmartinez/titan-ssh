package io.github.danielperezmartinez.titanssh.keyinstall

/**
 * Why installing a public key on a host failed (ADR-0030): the closed catalog
 * of [[Instalar la clave pública en el host desde la app]]. Each entry has the
 * [title] the user sees and the [advice] on what to do; the detail that comes
 * with a failure (the methods the server offers, the jump host, the error the
 * remote system gave) goes after them.
 *
 * [passwordSent] says whether the password had already gone to the server when
 * the step failed, which the summary states.
 */
enum class KeyInstallProblem(val title: String, val advice: String, val passwordSent: Boolean = true) {
    UNREACHABLE(
        "No se ha podido conectar con el host",
        "Comprueba el nombre o la IP, el puerto y que el equipo esté encendido y en la red.",
        passwordSent = false,
    ),
    NO_COMMON_ALGORITHM(
        "El servidor no tiene ningún algoritmo en común con la app",
        "Suele pasar con servidores SSH muy antiguos. Actualiza OpenSSH en el host.",
        passwordSent = false,
    ),
    HOST_KEY_DECLINED(
        "Has rechazado la clave del servidor",
        "No se ha enviado la contraseña. Comprueba la huella con el administrador del host y vuelve a intentarlo.",
        passwordSent = false,
    ),
    HOST_KEY_CHANGED(
        "La clave del servidor ha cambiado",
        "Podría ser alguien haciéndose pasar por él, así que no se ha enviado nada. Si sabes por qué ha cambiado " +
            "(reinstalación), abre una sesión del host y acepta la clave nueva desde allí.",
        passwordSent = false,
    ),
    HOST_KEY_NOT_TRUSTED(
        "El host no es de confianza todavía",
        "La política known_hosts del host es STRICT y su clave no está guardada. Cámbiala a TOFU o conéctate " +
            "antes para confiar en él. No se ha enviado la contraseña.",
        passwordSent = false,
    ),
    JUMP_CREDENTIALS_MISSING(
        "Faltan las credenciales del salto",
        "El host salta por otro cuya contraseña o clave no está en el almacén. Créala en ese host primero.",
        passwordSent = false,
    ),
    JUMP_UNREACHABLE(
        "No se ha podido pasar por el salto",
        "O no se llega al bastión, o el bastión no llega al host. Comprueba los dos.",
        passwordSent = false,
    ),
    JUMP_AUTH_FAILED(
        "El salto no ha aceptado sus credenciales",
        "Es la autenticación del bastión, no la de este host: revisa la del host del salto.",
        passwordSent = false,
    ),
    PASSWORD_NOT_OFFERED(
        "El servidor no acepta contraseña",
        "Tiene desactivado el acceso por contraseña (PasswordAuthentication y KbdInteractiveAuthentication). " +
            "Instala la clave a mano con otro acceso, o activa la contraseña un momento en sshd_config.",
        passwordSent = false,
    ),
    WRONG_PASSWORD(
        "Usuario o contraseña incorrectos",
        "El servidor no dice cuál de los dos falla. Revisa el usuario del host y vuelve a escribir la contraseña.",
    ),
    SECOND_FACTOR(
        "El servidor pide un segundo factor",
        "Además de la contraseña pide otro dato (un código, una clave). Esta versión no sabe responderlo: " +
            "instala la clave a mano.",
    ),
    PASSWORD_EXPIRED(
        "La contraseña ha caducado",
        "El servidor pide cambiarla antes de entrar. Cámbiala desde una sesión con terminal y vuelve a intentarlo.",
    ),
    TOO_MANY_ATTEMPTS(
        "El servidor ha cortado por demasiados intentos",
        "Espera un poco antes de volver a intentarlo; algunos servidores bloquean la IP un rato (fail2ban).",
    ),
    NO_SHELL(
        "La cuenta no deja ejecutar órdenes ni usar SFTP",
        "Puede tener una shell restringida, ForceCommand o nologin. Instala la clave a mano.",
    ),
    UNKNOWN_SYSTEM(
        "No se reconoce el sistema del host",
        "No responde como Unix ni como Windows con OpenSSH (p. ej. un equipo de red). No se ha tocado nada.",
    ),
    NO_HOME(
        "El usuario no tiene carpeta personal",
        "Su HOME no existe o no se puede entrar en ella, así que no hay dónde poner ~/.ssh.",
    ),
    SSH_DIR_NOT_DIR(
        "~/.ssh existe pero no es una carpeta",
        "Hay un fichero con ese nombre. Renómbralo o bórralo y vuelve a intentarlo.",
    ),
    SSH_DIR_NOT_OWNED(
        "~/.ssh es de otro usuario",
        "sshd ignoraría las claves de una carpeta ajena. Pide que se corrija su propietario (chown).",
    ),
    READ_ONLY(
        "La carpeta personal es de solo lectura",
        "El sistema de ficheros del host no deja escribir. No se ha cambiado nada.",
    ),
    DISK_FULL(
        "No queda espacio en el host",
        "El disco o la cuota del usuario están llenos. Libera espacio y vuelve a intentarlo.",
    ),
    FILE_NOT_WRITABLE(
        "No se puede escribir en authorized_keys",
        "Puede estar protegido (chattr +i), ser de otro usuario o gestionarlo otra herramienta.",
    ),
    WINDOWS_ADMIN_DENIED(
        "No se puede escribir administrators_authorized_keys",
        "El usuario es administrador, así que su clave va en %ProgramData%\\ssh, y esta sesión no tiene permiso " +
            "para escribir allí.",
    ),
    WINDOWS_ACL_FAILED(
        "No se han podido ajustar los permisos del fichero en Windows",
        "Win32-OpenSSH solo lee administrators_authorized_keys si solo pueden tocarlo Administradores y SYSTEM. " +
            "Ejecuta icacls desde una consola de administrador.",
    ),
    CONNECTION_LOST(
        "La conexión se ha cortado a mitad",
        "No se sabe si la clave llegó a escribirse. Puedes repetirlo sin miedo: no se duplica.",
    ),
    KEY_UNAVAILABLE(
        "No se ha podido usar la clave de este dispositivo",
        "El almacén de claves del sistema no la ha dado. Prueba con otro alias o con una clave software.",
    ),
    PUBKEY_DISABLED(
        "La clave está copiada, pero el servidor no acepta claves",
        "Tiene PubkeyAuthentication desactivado. El host sigue con su autenticación de antes.",
    ),
    STRICT_MODES_HOME(
        "La clave está copiada, pero sshd la ignora por los permisos de la carpeta personal",
        "El grupo u otros pueden escribir en tu HOME y StrictModes lo rechaza. Ejecuta chmod go-w ~ en el host " +
            "y vuelve a intentarlo.",
    ),
    KEY_REJECTED(
        "La clave está copiada, pero el servidor no la acepta",
        "Causas posibles: el servidor no admite este tipo de clave (PubkeyAcceptedAlgorithms o un OpenSSH antiguo), " +
            "lee las claves de otro sitio (AuthorizedKeysFile o AuthorizedKeysCommand) o StrictModes rechaza los " +
            "permisos de ~/.ssh. El host sigue con su autenticación de antes.",
    ),
    UNEXPECTED(
        "Error inesperado",
        "Ha fallado algo que la app no esperaba. El detalle de abajo dice qué.",
    ),
}
