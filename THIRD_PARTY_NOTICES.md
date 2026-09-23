# Avisos de terceros

titan-ssh se distribuye bajo la licencia **GPL-3.0-or-later** (ver
[`LICENSE`](LICENSE)). Incluye o enlaza los componentes de terceros siguientes,
todos con licencias compatibles con la GPLv3. Cada uno conserva su licencia
original; los textos completos acompañan a cada artefacto (POM de Maven,
`LICENSE` del módulo Go o el fichero indicado).

Inventario revisado el 2026-09-24 sobre los classpaths de ejecución
`:desktopApp` (`runtimeClasspath`) y `:androidApp` (`releaseRuntimeClasspath`)
y sobre `agent/go.sum`. Ninguna dependencia Apache-2.0 incluye un fichero
`NOTICE` que haya que reproducir.

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
| sshj (`com.hierynomus:sshj`) | 0.39.0 | Apache-2.0 | Todas las plataformas |
| asn-one (`com.hierynomus:asn-one`) | 0.6.0 | Apache-2.0 | Todas las plataformas |
| EdDSA-Java (`net.i2p.crypto:eddsa`) | 0.3.0 | CC0-1.0 | Todas las plataformas |
| Bouncy Castle (`bcprov`, `bcpkix`, `bcutil`) | 1.78.1 | Bouncy Castle Licence (MIT) | Todas las plataformas |
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
| Biblioteca estándar y toolchain de Go | 1.22+ | BSD-3-Clause |
| `github.com/creack/pty` | 1.1.24 | MIT |

## Mantenimiento

Al añadir o actualizar una dependencia de ejecución, se revisa su licencia (y
si trae un `NOTICE`) y se actualiza esta tabla en el mismo cambio.
