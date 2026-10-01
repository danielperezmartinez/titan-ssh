# Auditorías de seguridad

titan-ssh custodia credenciales SSH y mantiene sesiones vivas en los destinos,
así que la seguridad es un pilar del producto (ver [[README]], "Propósito del
proyecto"). Este sistema fija **cómo** se revisa, de forma manual, que la app
sigue cumpliendo lo que promete. Las auditorías se hacen **solo cuando el
usuario las pide**, sin calendario. Cada una deja una nota en
`Auditorías de seguridad/`.

[[Auditorías de seguridad/Auditorías de seguridad.base|Abrir la vista de auditorías]]

> **Repositorio público.** Esta nota y las de cada auditoría son públicas. Los
> hallazgos sensibles van solo a avisos privados de GitHub (regla 5 del
> [[README]], "Vulnerabilidades y hallazgos de seguridad"). Por eso este
> checklist describe **qué** se comprueba, nunca qué falla hoy.

## Cómo se pide una auditoría

El usuario la pide en una sesión de cualquier CLI o agente, por ejemplo:
"haz una auditoría de seguridad rápida", "… estándar" o "… completa". Si no
dice el tipo, el agente propone uno según lo que haya cambiado desde la
última auditoría y espera la respuesta.

Momentos en que conviene pedirla (son sugerencias, no obligaciones):

- **Rápida** antes de publicar una versión que toque las áreas del punto A.4.
- **Estándar** cuando se hayan acumulado varias versiones desde la última.
- **Completa** tras un cambio de fondo: un método de autenticación nuevo, el
  protocolo o el transporte del agente, un canal de distribución nuevo, o el
  almacén de secretos.

## Cómo hacer una auditoría

1. Si hay una auditoría `En curso` en la vista, continuarla. Si no, crear la
   nota desde `Plantillas/Auditoría de seguridad.md` con el nombre
   `Auditoría AAAA-MM-DD <Tipo>`, en estado `En curso`.
2. Ejecutar el nivel del tipo pedido y los anteriores: una `Rápida` es el
   nivel A, una `Estándar` los niveles A y B, y una `Completa` los tres.
3. Antes de dar algo por hallazgo nuevo, leer los avisos privados abiertos
   (ver "Trabajar con los avisos privados") para no duplicar ninguno. Si un
   hallazgo ya tiene aviso, se amplía ese aviso.
4. Clasificar cada hallazgo con el criterio de la regla 5:
   - **Sensible** → aviso privado en borrador (ver abajo). En la nota solo va
     la fila `SEC-AAAA-NN` con severidad, estado y el `GHSA-…`.
   - **No sensible** → tarea normal, enlazada desde `Hallazgos públicos` y
     colocada en [[Seguimiento de tareas pendientes]].
5. Severidad:
   - **Alta**: un tercero puede suplantar al servidor, leer credenciales o
     sesiones, o ejecutar órdenes.
   - **Media**: fuga de datos, o ejecución que necesita una condición poco
     habitual.
   - **Baja**: endurecimiento, o información de poco valor.
6. Comprobar también los avisos ya existentes cuyo arreglo se haya publicado,
   y proponer al usuario publicarlos.
7. Cerrar la nota cuando cada hallazgo tenga destino.

## Trabajar con los avisos privados

Los avisos en borrador solo los ve quien administra el repositorio, así que un
agente necesita un **token del usuario** para leerlos o crearlos:

- Un *fine-grained token* limitado a este repositorio, con el único permiso
  **Repository security advisories**: lectura para leerlos, y lectura y
  escritura para crearlos o editarlos. Con caducidad corta.
- El usuario lo guarda en su terminal, nunca en el chat, en la variable de
  usuario `TITAN_ADVISORY_TOKEN`:
  `[Environment]::SetEnvironmentVariable('TITAN_ADVISORY_TOKEN','<token>','User')`.
  El agente la lee del registro, no de su propio entorno, así que funciona sin
  reiniciar la sesión.
- Con el trabajo terminado, el usuario borra la variable y revoca el token.

API REST de GitHub (`/repos/danielperezmartinez/titan-ssh/security-advisories`):

- **Listar** los avisos con `GET …?state=draft` y leer uno con `GET …/<GHSA>`.
- **Crear** uno con `POST …`. Los campos son: `summary`, `description`,
  `severity`, `cwe_ids` y `vulnerabilities` (paquete `other` / `titan-ssh`,
  con el rango de versiones afectadas).
- **Editar** con `PATCH …/<GHSA>`.

Los avisos se escriben en inglés: serán públicos cuando se publiquen. Después
de crearlos, se comprueba que una petición sin autenticar no los ve.

Sin el token, el agente da el borrador en el chat y el usuario lo da de alta a
mano en *Security* → *Advisories* → *New draft security advisory*.

Ningún agente **publica** ni **cierra** un aviso sin que el usuario lo pida en
esa sesión.

## Corregir un hallazgo privado

1. Leer su aviso con el token. Es la única fuente del detalle.
2. Trabajar en un worktree o rama local. **No se sube nada** hasta que el
   arreglo esté listo para publicarse: los commits se suben junto con el tag
   de la versión, así el código del arreglo está a la vista el menor tiempo
   posible antes de la publicación.
3. Mensajes de commit, nombres de rama y comentarios **neutros**, que no
   expliquen el fallo (p. ej. "Harden host key checks"). Los tests que lo
   cubren no llevan nombres que lo describan.
4. Verificar como cualquier otro cambio: tests y prueba en el emulador (regla
   4, paso 0).
5. Publicar la versión cuando el usuario lo pida (regla 4). Después, si el
   usuario lo aprueba, publicar el aviso con
   `patched_versions` = esa versión, actualizar su fila en la auditoría y
   añadir aquí su comprobación de regresión.

## Modelo de amenazas resumido

Qué se protege:

- Las credenciales: contraseñas, claves software, passphrases y el uso de la
  clave hardware.
- La identidad del servidor: que el usuario habla con el host que cree.
- Las sesiones: lo que se teclea y lo que muestran el terminal, el scrollback
  y el buffer del agente.
- La integridad de lo que se ejecuta: la app publicada y el binario del agente
  que se sube al destino.
- Los metadatos: hosts, usuarios, scripts y túneles de la configuración.

Frente a quién:

- **La red** entre cliente y destino (MITM, wifi pública, router comprometido).
- **Otro usuario local** del destino o del equipo cliente.
- **Otra app** del mismo dispositivo Android.
- **Quien tenga el dispositivo** desbloqueado o una copia de seguridad suya.
- **La cadena de suministro**: dependencias, CI, artefactos y canales de
  distribución.
- **Contenido hostil** que llega al terminal: salida del servidor o texto del
  portapapeles.

Fuera de alcance: un destino ya comprometido (root en el host) y el propio
usuario como atacante de su cuenta.

## Nivel A · Rápida (unos 30 min)

Revisa lo que ha cambiado desde la última auditoría o el último tag. Si se
hace antes de una publicación y sale un hallazgo `Alta`, el agente lo dice
antes de seguir con la publicación, y decide el usuario.

1. **Dependencias con vulnerabilidades conocidas.**
   - Kotlin/Gradle: `osv-scanner scan source -r .` (lee
     `gradle/libs.versions.toml` y los lockfiles que haya).
   - Agente Go: `govulncheck ./...` en `agent/`.
   - Avisos abiertos de Dependabot en la pestaña *Security*, si está activo.
2. **Secretos y datos personales** en el diff de la versión y en el historial:
   `gitleaks git --log-opts="<última-auditoría-o-tag>..HEAD"`, además de la comprobación
   rápida de la regla 5.
3. **Workflows de GitHub Actions**: `zizmor .github/workflows/` (acciones sin
   fijar por SHA, permisos de más, inyección de expresiones, secretos
   expuestos).
4. **Revisión manual del diff** de cualquier cambio que toque: `ssh/`,
   `secret/`, `terminal/Agent*`, `terminal/ScriptRunner*`,
   `terminal/TerminalKeys*`, `agent/`, `androidApp/src/main/AndroidManifest.xml`,
   `.github/workflows/` o los ficheros de empaquetado.
5. **Avisos privados abiertos**: repasar si alguno se corrige en esta versión.
   Si es así, preparar su publicación junto al Release.

## Nivel B · Estándar (medio día)

Se hace con el servidor de pruebas de `tools/test-sshd/` y el emulador
`Pixel_9_Pro_XL`, nunca contra máquinas del usuario sin su permiso.

1. **Algoritmos que ofrece el cliente.** `ssh-audit -c -p 2223` deja un
   servidor falso escuchando, y se conecta la app a `127.0.0.1:2223` (en el
   emulador, `10.0.2.2:2223`). Todo lo que salga en rojo o amarillo se evalúa.
2. **Identidad del servidor (MITM).** Contra el contenedor de pruebas, después
   de haber confiado su clave: cualquier cambio de la identidad que presenta el
   servidor respecto a lo guardado tiene que acabar en una alerta que bloquea,
   nunca en la pregunta de "host nuevo" ni en una conexión silenciosa. Probar
   varias formas de cambio, no solo una.
3. **Opciones de seguridad configurables.** Se recorren los editores de host,
   sesión, script y túnel, y cada opción que afecte a la seguridad se
   comprueba en una conexión real: hace lo que dice, o la interfaz no la
   ofrece.
4. **Agente en un destino con dos usuarios.** En un contenedor con dos cuentas,
   la segunda no puede alcanzar el daemon de la primera: ni leer su token, ni
   conectar a su puerto con éxito, ni sustituir su binario o su directorio de
   estado.
5. **Contenido hostil en el terminal.** La salida del servidor y el texto
   pegado no pueden hacer que el terminal envíe órdenes que el usuario no
   tecleó (respuestas a secuencias de escape, pegado, títulos).
6. **Secretos en tránsito y en reposo.** Se sigue el camino de cada tipo de
   secreto desde el almacén hasta su uso, y se comprueba que no queda en claro
   en ningún sitio por el que pase, en el cliente ni en el destino. Revisar
   también los permisos de los ficheros que crea la app en cada plataforma.
7. **Android.**
   - Escaneo de la APK de release con MobSF (estático).
   - Lint de Android con las comprobaciones de seguridad.
   - Copias de seguridad y transferencia entre dispositivos: qué ficheros
     salen.
   - Captura de pantalla y vista de apps recientes con una sesión abierta.
   - Componentes exportados y permisos del manifiesto.
8. **Agente estático.** `gosec ./...` en `agent/`, y `go test -race ./...`
   (en un contenedor de Go si el equipo no tiene la toolchain).
9. **Dependencias al día.** Comparar sshj, BouncyCastle, java-keyring y
   `golang.org/x/sys` con su última versión, y leer sus notas de seguridad.
10. **Checklist OWASP MASVS** (v2, perfil L1): repasar las categorías STORAGE,
    CRYPTO, AUTH, NETWORK, PLATFORM y CODE contra los cambios desde la última auditoría.

## Nivel C · Completa

Revisa el diseño, no solo lo que ha cambiado.

1. Rehacer el **modelo de amenazas** de arriba (STRIDE por componente:
   cliente, almacén de secretos, conexión SSH, túneles, agente, pipeline) y
   actualizar esta nota.
2. Repasar que siguen vigentes las ADR de seguridad:
   [[ADR-0001 Credenciales en almacén nativo del SO]],
   [[ADR-0005 Autenticación SSH y verificación de host]],
   [[ADR-0008 Diseño del agente de resiliencia nivel 3]],
   [[ADR-0009 Agente de nivel 3 portable a todos los destinos]] y
   [[ADR-0010 Empaquetado del agente y descarga bajo demanda]].
3. Revisión de código completa de las áreas del nivel A.4, no solo del diff.
4. Cadena de suministro: OpenSSF Scorecard del repositorio, firma y
   procedencia de los artefactos, custodia de los secretos de CI y de las
   claves de firma.
5. Valorar una revisión externa (otra persona o un agente en una sesión
   distinta, sin el contexto de quien escribió el código).

## Herramientas

Todas gratuitas, en línea con la regla 4 del [[README]]:

| Herramienta | Para qué | Nivel |
| --- | --- | --- |
| `osv-scanner` | CVE de dependencias (Gradle y Go) | A |
| `govulncheck` | CVE alcanzables desde el código del agente | A |
| `gitleaks` | Secretos en el diff y en el historial | A |
| `zizmor` | Seguridad de los workflows de GitHub Actions | A |
| `ssh-audit -c` | Algoritmos que ofrece el cliente SSH | B |
| MobSF | Análisis estático de la APK | B |
| `gosec` | Análisis estático del agente | B |
| Android Lint | Comprobaciones de seguridad de Android | B |
| OpenSSF Scorecard | Prácticas de seguridad del repositorio | C |

Referencias: OWASP MASVS y MASTG (apps móviles), las guías de configuración
de OpenSSH y la política de `ssh-audit` (algoritmos), y el modelo STRIDE.

## Evolución de este checklist

- Cada auditoría propone en su sección "Cambios para el checklist" lo que
  convenga añadir. Se incorpora aquí en el mismo cambio que cierra la
  auditoría.
- Una comprobación de regresión ligada a un hallazgo privado se añade **cuando
  su aviso se publica**, nunca antes.
