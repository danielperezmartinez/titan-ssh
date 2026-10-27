---
Nombre: 'Cliente RDP embebido con IronRDP por un túnel SSH'
Número: 28
Estado: 'Rechazada'
Resumen: 'Rechazada el 2026-10-27: tras compararlas en la canary, el usuario eligió el escritorio remoto propio (ADR-0029), que estaba terminado, y se retiró todo lo de esta opción. Proponía un nuevo tipo de sesión RDP: titan-ssh abre un canal direct-tcpip de la conexión SSH hacia el servidor RDP del destino (localhost:3389 por defecto) y pinta el escritorio con un cliente RDP embebido. El motor es IronRDP (Rust, MIT/Apache-2.0), en una biblioteca nativa propia (crate rdp/) con una superficie JNI mínima escrita con jni-rs, sin callbacks hacia la JVM ni puerto local. Se compila con cargo desde Gradle para Android (arm64-v8a y x86_64) y escritorio (Linux x64 y Windows x64 en CI), igual que el agente: opcional en desarrollo y obligatoria en una release. El certificado TLS del servidor se confirma por su huella SHA-256 antes de enviar ninguna credencial, como la clave de host de ADR-0005, y la contraseña vive en el SecretStore (ADR-0001). NLA es el modo por defecto; el inicio de sesión TLS sin NLA (xrdp) es opcional por sesión.'
Decisión: 'Sesión SessionType.RDP con un cliente IronRDP embebido vía JNI (jni-rs), transportado por un canal direct-tcpip de la conexión SSH, con huella del certificado confirmada por el usuario y contraseña en el SecretStore.'
Consecuencias: 'El proyecto pasa a tener código nativo: Rust y el NDK entran en la cadena de build local y de CI, y cada paquete de la canary lleva la biblioteca de su plataforma (unos 6-7 MB por ABI). Mientras se compara con la opción D solo la canary la lleva y ofrece el tipo; la estable no. No hay puerto local que otras apps puedan usar. El escritorio de Windows y xrdp funcionan sin instalar nada en el destino. Portapapeles, audio, unidades y multimonitor quedan fuera por ahora. Android de 32 bits no tiene RDP.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-21T11:00:00+02:00
Última modificación: 2026-10-27T10:00:00+01:00
---

# ADR-0028 · Cliente RDP embebido con IronRDP por un túnel SSH

> **Rechazada el 2026-10-27.** Las opciones C (esta) y D
> ([[ADR-0029 Escritorio remoto propio a través del agente]]) se probaron a la
> vez en la canary. El usuario se quedó con la D, que estaba terminada, y se
> aplicó la retirada de §8: se borraron `rdp/`, `SessionType.RDP`,
> `RdpSettings`, el código de `rdp/` en `shared`, `openDirect()` (solo lo usaba
> este cliente), las tareas de Gradle, los pasos de Rust y NDK de CI,
> `tools/test-rdp/`, la sección de IronRDP de `THIRD_PARTY_NOTICES.md` y su
> entrada del catálogo técnico. `ConfigMigration` descarta las sesiones RDP que
> guardó la canary. El resto de esta nota se conserva como registro.

## Contexto

El usuario quiere ver y manejar el escritorio de sus equipos desde titan-ssh,
sobre la misma conexión SSH que ya usa para la terminal, el mouse pad
([[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]]) y SFTP. Se
estudian dos caminos en paralelo: un escritorio remoto propio sobre el agente
(opción D, otra sesión de trabajo) y un cliente RDP estándar (opción C, esta
decisión). RDP tiene la ventaja de que Windows ya trae el servidor y en Linux
basta con xrdp o gnome-remote-desktop: no hay que instalar nada de titan-ssh en
el destino.

Restricciones:

- **En la JVM no hay un cliente RDP moderno.** Las bibliotecas Java (derivadas
  de properJavaRDP) no implementan NLA/CredSSP, que Windows exige por defecto
  desde hace años, ni los códecs actuales.
- **Seguridad** (pilar del producto): las credenciales no se guardan en claro
  ([[ADR-0001 Credenciales en almacén nativo del SO]]) y un servidor nuevo se
  confirma antes de confiar en él
  ([[ADR-0005 Autenticación SSH y verificación de host]]).
- **Licencia** GPL-3.0-or-later y **coste cero** (regla 4 del README).
- **Plataformas**: Android (arm64 en los teléfonos, x86_64 en el emulador),
  Linux x64 y Windows x64 ([[ADR-0002 Stack KMP y alcance multiplataforma]]).
- El proyecto **no tenía código nativo**: todo era Kotlin/JVM y el agente Go,
  que es un binario aparte para el destino.

## Decisión

### 1. Motor: IronRDP

El protocolo lo implementa **IronRDP** (Devolutions, Rust, MIT o Apache-2.0,
compatibles con GPL-3.0-or-later). Se usan sus crates de cliente por separado
(`ironrdp-connector`, `-session`, `-graphics`, `-input`, `-tokio`, `-dvc`,
`-displaycontrol`) y no el crate de aplicación `ironrdp-client`, que arrastra
WebSocket, reqwest y ventanas que aquí sobran. Cubre la negociación X.224,
TLS, NLA con CredSSP (NTLM; Kerberos no, porque necesitaría un KDC), los
códecs de mapa de bits y RemoteFX, el canal de cambio de tamaño del escritorio
y el puntero dibujado en el propio fotograma.

TLS va con **rustls y el proveedor `ring`** (no `aws-lc-rs`), porque `ring` se
compila para Android solo con el clang del NDK. Consecuencia: rustls no habla
TLS 1.0/1.1, así que servidores muy antiguos (Windows 7 o 2008 R2 sin
actualizar) no conectan.

### 2. Transporte: un canal SSH, sin puerto local

La sesión RDP abre un canal `direct-tcpip` de la conexión SSH de la pestaña
hacia el servidor RDP **visto desde el destino**: `localhost:3389` por defecto,
o el host y puerto que diga la sesión (por ejemplo otro equipo de la red del
destino). Los bytes del canal se pasan al motor por memoria (`feed`/`drain`);
**no se abre ningún puerto en el dispositivo**. Con un túnel local normal
cualquier otra app de Android, u otro usuario del equipo, podría conectarse a
ese puerto mientras la sesión está abierta y llegar al servidor RDP sin pasar
por SSH.

El canal sigue el ciclo de vida de la pestaña (reconexión de nivel 1 de
[[ADR-0003 Modelo de resiliencia por niveles]]): si la conexión SSH cae, la
sesión RDP se vuelve a negociar en la conexión nueva. Windows y xrdp
reenganchan la misma sesión de usuario, así que no se pierde el escritorio.

### 3. Bindings: JNI escrito a mano con jni-rs

La biblioteca `titan_rdp` expone una docena de funciones JNI de
`io.github.danielperezmartinez.titanssh.rdp.RdpNative`, escritas con `jni-rs`:

- **Solo Kotlin llama a Rust.** No hay callbacks: Kotlin empuja los bytes que
  llegan (`feed`), saca los que hay que enviar (`drain`, bloqueante, en un hilo
  propio) y recoge los eventos (`nextEvent`: certificado, tamaño del
  escritorio, fotograma nuevo, fin) en otro hilo. Los píxeles se copian con
  `copyFrame` al array del fotograma, solo el rectángulo que cambió.
- Cada sesión es un **número en un registro**, no un puntero: un handle
  repetido o ya liberado no hace nada. Cada entrada captura los `panic` de
  Rust para que ninguno llegue a la JVM.
- El motor corre en un hilo propio con un runtime Tokio de un solo hilo.

Se descarta **UniFFI**: genera bindings sobre JNA, que habría que llevar con su
`libjnidispatch` para cada ABI de Android, y su modelo de callbacks es más
pesado que el de esta superficie tan pequeña.

### 4. Cadena de build nativo

- El crate vive en `rdp/` con su `Cargo.lock` versionado; se compila con
  `cargo build --release --locked`.
- **Android**: una tarea de Gradle de `:androidApp` compila para
  `aarch64-linux-android` y `x86_64-linux-android` con el clang del NDK
  (API 26, la `minSdk`) y entrega las `.so` a AGP como `jniLibs` generadas. No
  hace falta `cargo-ndk`. El NDK se fija a una versión (r29) y se instala con
  `sdkmanager`.
- **Escritorio**: una tarea de `:desktopApp` compila para el sistema que
  construye y deja la biblioteca en los recursos de la app de Compose
  (`linux-x64/`, `windows-x64/`), que acaban dentro de cada paquete.
- **Como el agente** (ADR-0010): en desarrollo, sin Rust o sin NDK el build
  sigue con un aviso y la app dice que esa build no tiene RDP; una build de
  release falla si no puede compilar la biblioteca.
- **Solo en la canary** mientras se compara con la opción D: las tareas de
  `:androidApp` y `:desktopApp` solo compilan la biblioteca con
  `titanChannel == "canary"` (el mismo criterio que usa la opción D para el
  agente). Una build estable, de desarrollo o de release, no la lleva ni
  necesita Rust, y `SessionType.RDP` es `experimental`, así que el editor de
  la estable no ofrece el tipo.
- **CI** (`release.yml`): en un run de la canary, los jobs `android`, `linux`
  y `windows` instalan la toolchain de Rust y los targets que necesitan, y el
  de Android además el NDK fijado. En un run estable esos pasos se saltan. El
  job `test` pasa los tests del motor (`cargo test`) en los dos canales. La
  versión de Rust se fija en `rdp/rust-toolchain.toml`.

### 5. Empaquetado

- **APK**: `lib/arm64-v8a` y `lib/x86_64`. Los móviles Android de 32 bits
  (`armeabi-v7a`) no tienen RDP; la sesión lo dice al abrirse.
- **MSI, deb, rpm y tar.gz**: la biblioteca va en los recursos de la app
  (`<instalación>/lib/app/resources/`) y se carga desde ahí con
  `System.load`, sin extraerla a una carpeta escribible. **Flatpak y AUR**
  parten del tar.gz, así que la heredan sin cambios.
- Tamaño: unos 6-7 MB por ABI sin comprimir, con LTO y símbolos quitados.
- `THIRD_PARTY_NOTICES.md` añade IronRDP y las dependencias Rust con licencia
  propia.

### 6. Seguridad

- **Certificado del servidor**: los servidores RDP usan certificados
  autofirmados, así que no se valida una cadena. Se comprueban las firmas del
  handshake TLS con la clave del certificado y, **antes de enviar ninguna
  credencial**, la app compara la huella SHA-256 del certificado con la
  guardada para ese destino: la primera vez la muestra y el usuario decide
  (TOFU, como la clave de host de ADR-0005); si cambia, avisa y no conecta
  hasta que el usuario acepte la nueva. Las huellas se guardan en un fichero
  propio junto a `known_hosts`.
- **NLA (CredSSP) siempre se ofrece y es lo que se usa** si el servidor lo
  admite: autentica antes de abrir la sesión gráfica y ata las credenciales a
  la clave pública del certificado. El modo **TLS sin NLA** (inicio de sesión
  del propio servidor, el de xrdp) solo se ofrece si la sesión lo permite
  expresamente; en ese modo las credenciales viajan en el Client Info PDU para
  entrar directo.
- **Credenciales**: usuario y dominio en la configuración; la contraseña en el
  SecretStore, por referencia, como la de SSH
  ([[ADR-0001 Credenciales en almacén nativo del SO]]). Se lee al conectar y
  se borra de la memoria de Kotlin al pasarla al motor.
- Todo el código nuevo que toca datos del servidor es Rust (sin `unsafe`
  salvo las tres conversiones de `i8`/`u8` de la frontera JNI).

### 7. Modelo y sesión

`SessionType` gana `RDP`, y `Session` un campo `rdp: RdpSettings` con valor por
defecto: los documentos anteriores se leen igual y **no cambia la versión de
configuración** (como `MOUSEPAD` en ADR-0016). De la sesión solo se usan el
host SSH y los ajustes RDP; scripts, túneles, nivel de resiliencia y carpeta
se conservan sin usarse, como en el mouse pad y SFTP.

### 8. Comparación en la canary y retirada

La canary lleva a la vez esta opción y la D, cada una como un tipo de sesión
propio, para que el usuario las pruebe en sus dispositivos. La estable solo
llevará la que elija, ya limpia. El mecanismo común es
`SessionType.experimental` (un tipo experimental solo se ofrece si
`AppChannel.isCanary`).

- **Si se elige C**: `RDP` deja de ser `experimental` y las tareas de Gradle y
  los pasos de CI compilan la biblioteca en todos los canales. Esta ADR pasa a
  `Aceptada`.
- **Si se descarta C**: se borran `rdp/`, `SessionType.RDP` y `RdpSettings`
  (con una migración que lea y descarte el campo `rdp` de las sesiones
  guardadas en la canary), el código de `rdp/` en `shared`, las tareas de
  Gradle, los pasos de Rust y NDK de CI, `tools/test-rdp/`, la sección de
  IronRDP de `THIRD_PARTY_NOTICES.md` y sus entradas del catálogo técnico.
  Esta ADR pasa a `Rechazada`.

### 9. Fuera de alcance por ahora

Portapapeles, audio, redirección de unidades e impresoras, varios monitores,
Kerberos, la puerta de enlace RD Gateway y los códecs GFX/H.264. Se pueden
añadir con los crates de IronRDP que ya existen (`ironrdp-cliprdr`,
`ironrdp-rdpsnd`, `ironrdp-rdpdr`) cuando el usuario los pida.

## Alternativas consideradas

- **FreeRDP (C, Apache-2.0)** — el cliente libre más completo y el que usa
  aFreeRDP en Android. Descartado: compilarlo para Android y Windows con
  OpenSSL y sus dependencias es mucho más pesado, la biblioteca es mayor y es
  código C expuesto a lo que mande el servidor. IronRDP cubre lo que se
  necesita en Rust y se cruza con `cargo`.
- **Cliente RDP en la JVM** (properJavaRDP y derivados) — sin NLA ni códecs
  modernos; Windows lo rechaza con la configuración por defecto.
- **UniFFI** para los bindings — ver §3.
- **Túnel local a 127.0.0.1** y que el motor abra su propio socket — más simple,
  pero deja un puerto abierto a otras apps y usuarios (§2).
- **Pasarela en el destino** (Guacamole, noVNC) — obliga a instalar y mantener
  un servidor más en cada destino.
- **Escritorio remoto propio sobre el agente** (opción D) — se estudia en otra
  sesión; no es excluyente: RDP sirve para lo que ya trae servidor (Windows) y
  el modo propio para lo que no.

## Consecuencias

- Positivas:
  - Escritorio de Windows (y de Linux con xrdp) en una pestaña, sin instalar
    nada en el destino y sin abrir el puerto RDP a la red: solo se llega por
    SSH.
  - El motor es Rust con licencia permisiva y mantenido por una empresa que lo
    usa en producción.
  - El mismo código sirve para Android y escritorio.
- Negativas / compromisos:
  - Rust, el NDK y una toolchain más en local y en CI; el primer build nativo
    tarda unos minutos.
  - Unos 6-7 MB más por ABI en cada paquete.
  - Sin Android de 32 bits ni TLS 1.0/1.1.
  - El fotograma se copia a la JVM en cada actualización (el rectángulo que
    cambia); con resoluciones altas a muchos fotogramas por segundo pesa en
    móviles. Si hace falta, se pasará a una superficie nativa (Android
    `Surface`/`ANativeWindow`).
  - Se encontró un fallo de IronRDP 0.17 con mapas de bits comprimidos cuya
    anchura no coincide con la del rectángulo (la pantalla de inicio de sesión
    de xrdp se ve torcida). Con NLA o con el inicio directo de §6 no aparece;
    queda para comunicarlo aguas arriba.
  - xrdp 0.10 cambia el tamaño del escritorio con un Deactivate All PDU que
    solo trae la cabecera Share Control, sin el `shareId` que IronRDP espera
    (FreeRDP lo acepta). El motor lo reconoce y hace la reactivación él mismo;
    también queda para comunicarlo aguas arriba.
- Relacionada: [[Sesión RDP embebida con IronRDP]].
