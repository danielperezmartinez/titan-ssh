---
Nombre: "SshConnector"
Tipo: "Contrato"
Área: "Conexión SSH"
Feature: "Conexión"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/SshSession.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.ssh"
Resumen: "Primitiva de conexión SSH. SshConnector.connect(endpoint, credentials, hostKeyVerifier, keepAlive, via) abre una SshSession autenticada; via es la cadena de saltos ProxyJump (SshHop: endpoint, credenciales, verificador y keepalive propios de cada bastión), y con ella el destino se alcanza solo por un canal direct-tcpip del último salto (sshj connectVia), nunca directo; la sesión es dueña de los saltos, los cierra al cerrarse y cae si cae uno de ellos; SshSession expone estado observable, openShell() y exec(command); SshShell da flujo de salida, envío de entrada y resize; SshExecChannel corre un comando sin PTY (stdout/stderr/stdin crudos + exit status), transporte del agente de resiliencia nivel 3 (ADR-0008). openSftp() abre un SshSftp mínimo (canonicalize, size, list, mkdirs, write, read, chmod, rename, remove) o devuelve null si el servidor no tiene subsistema sftp; es la vía con la que AgentInstaller sube el agente a cualquier destino, Windows incluido (ADR-0009 §7). Verificador de host inyectable (fingerprint SHA256) y credenciales password/clave desde el SecretStore. Impl sshj en jvmShared; se obtiene con createSshConnector(). Verificada contra un host real (handshake, auth por clave, shell PTY); el canal exec tiene test de integración opt-in. openForward(PortForward, onProblem) abre un túnel sobre la sesión: Local, Remote o DynamicSocks (proxy SOCKS 4/4a/5 propio, solo CONNECT y sin autenticación); devuelve un SshForward que se cierra sin cerrar la sesión, lanza SshForwardFailed con un ForwardFailure (puerto ocupado, sin permiso, dirección no válida, el servidor lo rechaza) y avisa por onProblem (ForwardProblem) de cada conexión que no se completa. Test de integración opt-in de los tres tipos contra sshd real (SshjForwardingIntegrationTest). Los algoritmos que ofrece la impl sshj están fijados en SshjConfig.kt; si el host no comparte ninguno de un tipo, connect lanza SshNoCommonAlgorithm con su SshAlgorithmKind (intercambio de claves, clave de host, cifrado, MAC o compresión), y reintentar no sirve."
Última modificación: 2026-10-02T13:30:00+02:00
---

# SshConnector

Después de descubrir esta pieza en el catálogo, consulta su contrato en
[SshSession.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/SshSession.kt)
(interfaces `SshConnector` / `SshSession` / `SshShell` / `SshExecChannel` / `SshSftp`) y la implementación sshj en
[SshjConnector.kt](../../shared/src/jvmSharedMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/SshjConnector.kt),
que son la fuente de verdad.

Verificada de punta a punta contra un host real (ver [[Motor de conexión SSH]]).

Piezas relacionadas:

- [[SecretStore]] — custodia las credenciales que alimentan `SshCredentials`.
- La verificación `known_hosts` (TOFU) y el signer en hardware se enchufan aquí
  desde [[Autenticación SSH signer en hardware y verificación de host]].
- `SshSftp` (`openSftp()`) es la subida del agente de nivel 3; la
  implementación sshj trocea las escrituras por el tamaño máximo de paquete
  (una sola escritura grande desborda la ventana del canal). Ver [[AgentInstaller]].
- `SshExecChannel` es el transporte del nivel 3 de resiliencia: sobre él corre el
  protocolo por tramas [[AgentProtocol]] (ver
  [[Nivel 3 canal exec en el motor SSH]] y
  [[ADR-0008 Diseño del agente de resiliencia nivel 3]]).
- `openForward()` abre los túneles de las sesiones
  ([[Ejecutar los túneles de las sesiones]]). La implementación sshj
  (`SshjForwards.kt`) usa su propio bucle de aceptación para el reenvío local y
  el SOCKS, porque el `LocalPortForwarder` de sshj no informa de las conexiones
  que fallan y sshj no trae SOCKS.
- Los algoritmos que ofrece la implementación sshj (intercambio de claves,
  clave de host y firma de usuario, cifrados y MAC) están fijados en
  [SshjConfig.kt](../../shared/src/jvmSharedMain/kotlin/io/github/danielperezmartinez/titanssh/ssh/SshjConfig.kt),
  con un test que comprueba la lista exacta. Si el host no comparte ninguno
  de un tipo, `connect` lanza `SshNoCommonAlgorithm` y la pestaña falla sin
  reintentar ([[SessionManager]]).
