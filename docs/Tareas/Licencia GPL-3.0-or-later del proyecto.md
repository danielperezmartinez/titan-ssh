---
Nombre: 'Licencia GPL-3.0-or-later del proyecto'
Estado: 'Pendiente'
Resumen: 'Publicar el proyecto con licencia GPL-3.0-or-later, titular Daniel Pérez Martínez. Hoy el repositorio es público pero no tiene LICENSE, así que legalmente es "todos los derechos reservados", y los canales gratuitos (SignPath Foundation, IzzyOnDroid, Flathub, F-Droid) exigen licencia libre. Incluye el texto de la licencia, los avisos de terceros (sshj, BouncyCastle, JetBrains Mono con OFL, dependencias Go), un apartado en el README y los avisos legales en la UI que exige la GPLv3 para interfaces interactivas (pantalla Acerca de).'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §1. Variante or-later elegida por el usuario (2026-09-23).'
Bloqueada: []
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-23T22:50:00+02:00
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

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
