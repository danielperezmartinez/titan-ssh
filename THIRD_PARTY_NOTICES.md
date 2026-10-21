# Avisos de terceros

titan-ssh se distribuye bajo la licencia **GPL-3.0-or-later** (ver
[`LICENSE`](LICENSE)). Incluye o enlaza los componentes de terceros siguientes,
todos con licencias compatibles con la GPLv3. Cada uno conserva su licencia
original; los textos completos acompañan a cada artefacto (POM de Maven,
`LICENSE` del módulo Go o el fichero indicado).

Inventario revisado el 2026-09-24 (y el 2026-10-02 para sshj y Bouncy Castle) sobre los classpaths de ejecución
`:desktopApp` (`runtimeClasspath`) y `:androidApp` (`releaseRuntimeClasspath`),
y el 2026-09-27 sobre `agent/go.sum`. Ninguna dependencia Apache-2.0 incluye un
fichero `NOTICE` que haya que reproducir.

## Cliente (Android y escritorio)

| Componente | Versión | Licencia | Uso |
| --- | --- | --- | --- |
| Kotlin stdlib (`org.jetbrains.kotlin`) | 2.4.20 | Apache-2.0 | Todas las plataformas |
| kotlinx.coroutines | 1.10.2 | Apache-2.0 | Todas las plataformas |
| kotlinx.serialization | 1.8.1 | Apache-2.0 | Todas las plataformas |
| Compose Multiplatform (`org.jetbrains.compose.*`, Skiko, JBR API) | 1.9.3 | Apache-2.0 | Todas las plataformas |
| AndroidX (Activity, Compose, Lifecycle, Core y dependencias) | varias | Apache-2.0 | Android |
| Guava `listenablefuture` | 1.0 | Apache-2.0 | Android |
| JetBrains `annotations`, JSpecify | varias | Apache-2.0 | Todas las plataformas |
| sshj (`com.hierynomus:sshj`) | 0.41.1 | Apache-2.0 | Todas las plataformas |
| asn-one (`com.hierynomus:asn-one`) | 0.6.0 | Apache-2.0 | Todas las plataformas |
| Bouncy Castle (`bcprov`, `bcpkix`, `bcutil`) | 1.86 | Bouncy Castle Licence (MIT) | Todas las plataformas |
| SLF4J API | 2.0.x | MIT | Todas las plataformas |
| java-keyring (`com.github.javakeyring`) | 1.0.4 | BSD-3-Clause | Escritorio |
| JNA y JNA Platform | 5.13.0 | Apache-2.0 (doble licencia con LGPL-2.1-or-later; se usa bajo Apache-2.0) | Escritorio |
| secret-service (`de.swiesend`) | 1.8.1 | MIT | Escritorio (Linux) |
| dbus-java | 4.2.1 | MIT | Escritorio (Linux) |
| HKDF (`at.favre.lib:hkdf`) | 1.1.0 | Apache-2.0 | Escritorio (Linux) |
| jkeychain (`pt.davidafsilva.apple`) | 1.1.0 | BSD-2-Clause | Escritorio (macOS, dependencia de java-keyring) |

## Fuentes tipográficas

| Componente | Licencia | Texto |
| --- | --- | --- |
| JetBrains Mono (Regular, Medium, Bold) | OFL-1.1 | [`third_party/JetBrainsMono/OFL.txt`](third_party/JetBrainsMono/OFL.txt) |

## Agente de nivel 3 (`agent/`, Go)

| Componente | Versión | Licencia |
| --- | --- | --- |
| Biblioteca estándar y toolchain de Go | 1.26+ | BSD-3-Clause |
| `golang.org/x/sys` (proyecto Go) | 0.48.0 | BSD-3-Clause |

## Cliente RDP embebido (`rdp/`, Rust)

La biblioteca nativa `titan_rdp` (ADR-0028) va dentro de la APK y de los
paquetes de escritorio de titan-ssh canary; la app estable no la lleva.
Inventario revisado el 2026-10-21 sobre
`cargo tree -e normal` de `rdp/Cargo.lock` (230 crates); todas las licencias
son compatibles con la GPLv3. Los textos completos acompañan a cada crate en
crates.io.

| Componente | Versión | Licencia |
| --- | --- | --- |
| IronRDP (`ironrdp-connector`, `-session`, `-graphics`, `-input`, `-pdu`, `-core`, `-tokio`, `-async`, `-dvc`, `-displaycontrol`, `-svc`, `-bulk`, `-error`) | 0.2–0.11 | MIT OR Apache-2.0 |
| sspi (Devolutions, CredSSP/NTLM) | 0.21.3 | MIT OR Apache-2.0 |
| rustls, tokio-rustls | 0.23.46, 0.26 | Apache-2.0 OR ISC OR MIT |
| ring | 0.17.14 | Apache-2.0 AND ISC |
| rustls-webpki, untrusted, libloading, iso7816-tlv | varias | ISC |
| tokio, bytes, tracing, nom, bitvec y otras | varias | MIT |
| curve25519-dalek, ed25519-dalek, x25519-dalek, subtle | varias | BSD-3-Clause |
| cryptoki, cryptoki-sys, flagset | varias | Apache-2.0 |
| ICU4X (`icu_*`, `zerovec`, `yoke`, `tinystr`…) | varias | Unicode-3.0 |
| jni (jni-rs) | 0.21.1 | MIT OR Apache-2.0 |
| Resto (unos 175 crates) | varias | MIT OR Apache-2.0, y alguna con Zlib, BSD-2-Clause o Unlicense como alternativa |

## Mantenimiento

Al añadir o actualizar una dependencia de ejecución, se revisa su licencia (y
si trae un `NOTICE`) y se actualiza esta tabla en el mismo cambio.
