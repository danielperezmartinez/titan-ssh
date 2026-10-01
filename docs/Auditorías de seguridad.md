# Auditorías de seguridad

titan-ssh custodia credenciales SSH y mantiene sesiones vivas en los destinos,
así que la seguridad es un pilar del producto (ver [[README]], "Propósito del
proyecto"). Este sistema fija **cómo y cada cuánto** se revisa, de forma
manual, que la app sigue cumpliendo lo que promete. Cada auditoría deja una
nota en `Auditorías de seguridad/`.

[[Auditorías de seguridad/Auditorías de seguridad.base|Abrir la vista de auditorías]]

> **Repositorio público.** Esta nota y las de cada auditoría son públicas. Los
> hallazgos sensibles van solo a avisos privados de GitHub (regla 5 del
> [[README]], "Vulnerabilidades y hallazgos de seguridad"). Por eso este
> checklist describe **qué** se comprueba, nunca qué falla hoy.

## Cómo hacer una auditoría

1. Buscar en la vista una auditoría `Planificada` para este periodo. Si no
   existe, crear la nota desde `Plantillas/Auditoría de seguridad.md` con el
   nombre `Auditoría AAAA-MM-DD <Tipo>`, y pasarla a `En curso`.
2. Ejecutar el nivel que toque (abajo) y los anteriores: una `Trimestral`
   incluye el nivel A, y una `Completa` incluye A y B.
3. Clasificar cada hallazgo con el criterio de la regla 5:
   - **Sensible** → el agente prepara el borrador del aviso y el usuario lo da
     de alta en GitHub (*Security* → *Advisories* → *New draft security
     advisory*). En la nota solo va la fila `SEC-AAAA-NN` con severidad, estado
     y el `GHSA-…`.
   - **No sensible** → tarea normal, enlazada desde `Hallazgos públicos` y
     colocada en [[Seguimiento de tareas pendientes]].
4. Severidad: **Alta** (un tercero puede suplantar al servidor, leer
   credenciales o sesiones, o ejecutar órdenes), **Media** (fuga de datos o
   ejecución que necesita una condición poco habitual), **Baja** (endurecimiento
   o información de poco valor).
5. Cerrar la nota cuando cada hallazgo tenga destino, y fijar
   `Próxima auditoría`.

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

## Nivel A · En cada release (unos 30 min)

Se ejecuta antes de crear el tag (paso 1 de la publicación, regla 4 del
[[README]]). Si sale un hallazgo `Alta`, no se publica hasta decidir con el
usuario.

1. **Dependencias con vulnerabilidades conocidas.**
   - Kotlin/Gradle: `osv-scanner scan source -r .` (lee
     `gradle/libs.versions.toml` y los lockfiles que haya).
   - Agente Go: `govulncheck ./...` en `agent/`.
   - Avisos abiertos de Dependabot en la pestaña *Security*, si está activo.
2. **Secretos y datos personales** en el diff de la versión y en el historial:
   `gitleaks git --log-opts="<tag-anterior>..HEAD"`, además de la comprobación
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

## Nivel B · Trimestral (medio día)

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
   (ver la memoria del agente para hacerlo en Docker).
9. **Dependencias al día.** Comparar sshj, BouncyCastle, java-keyring y
   `golang.org/x/sys` con su última versión, y leer sus notas de seguridad.
10. **Checklist OWASP MASVS** (v2, perfil L1): repasar las categorías STORAGE,
    CRYPTO, AUTH, NETWORK, PLATFORM y CODE contra los cambios del trimestre.

## Nivel C · Completa (anual o tras un cambio mayor)

Toca cuando cambia algo de fondo: un método de autenticación nuevo, el
protocolo o el transporte del agente, un canal de distribución nuevo, o el
almacén de secretos.

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
