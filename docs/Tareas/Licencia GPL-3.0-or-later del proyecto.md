---
Nombre: 'Licencia GPL-3.0-or-later del proyecto'
Estado: 'En curso'
Resumen: 'Publicar el proyecto con licencia GPL-3.0-or-later, titular Daniel Pérez Martínez. Hoy el repositorio es público pero no tiene LICENSE, así que legalmente es "todos los derechos reservados", y los canales gratuitos (SignPath Foundation, IzzyOnDroid, Flathub, F-Droid) exigen licencia libre. Incluye el texto de la licencia, los avisos de terceros (sshj, BouncyCastle, JetBrains Mono con OFL, dependencias Go), un apartado en el README y los avisos legales en la UI que exige la GPLv3 para interfaces interactivas (pantalla Acerca de).'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §1. Variante or-later elegida por el usuario (2026-09-23).'
Bloqueada: []
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-24T14:00:00+02:00
---

# Licencia GPL-3.0-or-later del proyecto

## Objetivo

Que el código sea **legalmente open source** para poder usar los canales y
servicios gratuitos de [[ADR-0011 Distribución y canales de publicación]], que
exigen una licencia libre aprobada por la OSI.

## Contexto

- Repositorio público `danielperezmartinez/titan-ssh`, sin `LICENSE` ni
  `COPYING`.
- Titular: **Daniel Pérez Martínez**. Identificador SPDX:
  `GPL-3.0-or-later`.
- Dependencias con licencias compatibles con GPLv3 (comprobarlas todas al
  hacerlo): sshj (Apache-2.0), BouncyCastle (licencia tipo MIT), kotlinx
  (Apache-2.0), Compose Multiplatform (Apache-2.0), JetBrains Mono
  (`third_party/JetBrainsMono/OFL.txt`, OFL-1.1) y, en el agente Go,
  `golang.org/x/sys` (BSD-3). Apache-2.0 es compatible con GPLv3 (no con GPLv2).
- La GPLv3 §0/§5.d pide que una interfaz interactiva muestre "avisos legales
  apropiados" (copyright, que no hay garantía, licencia y cómo ver el texto).

## Criterios de finalización

- `LICENSE` en la raíz con el texto oficial íntegro de la GPL-3.0 (de gnu.org,
  sin modificar).
- Aviso de copyright y licencia (`Copyright (C) 2026 Daniel Pérez Martínez`,
  `GPL-3.0-or-later`) en el `README.md` de la raíz y en `agent/README.md`.
- Decidir si se añaden cabeceras SPDX
  (`// SPDX-License-Identifier: GPL-3.0-or-later`) a los ficheros fuente:
  recomendable, pero opcional.
- Inventario de licencias de terceros (`THIRD_PARTY_NOTICES` o similar)
  incluido en los paquetes: Apache-2.0 exige conservar sus avisos `NOTICE`.
- Pantalla o diálogo **Acerca de** en la app (escritorio y Android) con versión,
  copyright, "sin garantía", enlace a la licencia y al código fuente, y las
  licencias de terceros.
- El MSI muestra la licencia (`licenseFile` de Compose Desktop) y los paquetes de
  Linux la declaran (`rpmLicenseType`, campo `license` de AUR y `project_license`
  del metainfo de Flatpak).

## Verificación

Parcial (2026-09-24, paso 1 de [[Seguimiento de tareas pendientes]]):

- `LICENSE`: texto de `https://www.gnu.org/licenses/gpl-3.0.txt` sin modificar
  (674 líneas, SHA-256 `3972dc97…fb36986`).
- Inventario de licencias sacado de los POM de la caché de Gradle para los
  classpaths `:desktopApp:runtimeClasspath` y
  `:androidApp:releaseRuntimeClasspath`, y del `LICENSE` del módulo Go. Todas
  son compatibles con la GPLv3 (Apache-2.0, MIT, BSD-2/3, CC0, Bouncy Castle,
  OFL-1.1; JNA se usa bajo su opción Apache-2.0). Ningún JAR Apache-2.0 trae
  `META-INF/NOTICE`.

## Resultado

Hecho (commit `de7dbc4`):

- `LICENSE` en la raíz.
- Apartado **Licencia** con copyright, GPL-3.0-or-later y el aviso de que no hay
  garantía, en `README.md` y en `agent/README.md`.
- `THIRD_PARTY_NOTICES.md` en la raíz con el inventario y la regla de
  mantenerlo al cambiar dependencias.
- Cabeceras SPDX en los ficheros fuente: **no se añaden por ahora** (son
  opcionales). La licencia queda declarada en `LICENSE`, en los README y en
  los metadatos de los paquetes.

Pantalla **Acerca de** (2026-09-24, paso 2, junto a
[[Versionado único desde tag de git]]):

- Se abre con `[i]` en la cabecera de la app (glifo añadido a
  [[Vocabulario ASCII ampliado y disciplina de color]]) y ocupa el área de
  contenido con `[<]` para volver. Pulsar un área también la cierra.
- Muestra versión y plataforma, `Copyright (C) 2026 Daniel Pérez Martínez`, el
  aviso de GPL-3.0-or-later, el de que no hay garantía, y el enlace al código
  fuente. En una versión publicada el enlace apunta al tag
  (`.../tree/vX.Y.Z`); en desarrollo, al repositorio.
- `LICENSE` y `THIRD_PARTY_NOTICES.md` se leen dentro de la app, sin red: la
  tarea `bundleLegalTexts` copia los ficheros de la raíz como recursos
  `/legal/`, así que no hay una segunda lista de licencias que mantener.
- Verificado en escritorio (Windows 10: pantalla, visor de la licencia) y en el
  emulador `Pixel_9_Pro_XL` (pantalla, visor de avisos, y el enlace abre el
  navegador). En Android, el `[i]` queda en parte bajo la barra de estado y
  solo responde en su mitad inferior; se arregla con
  [[Respetar las barras del sistema en Android]].

Pendiente, repartido en la hoja de ruta:

- Incluir `LICENSE` y `THIRD_PARTY_NOTICES.md` en los paquetes, más
  `licenseFile` del MSI y `rpmLicenseType`: paso 4,
  [[Configuración de release del escritorio]]. El campo `license` de AUR y el
  `project_license` de Flatpak van en sus tareas de canal.
