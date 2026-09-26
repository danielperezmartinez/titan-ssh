---
Nombre: 'Canal Linux Flatpak en Flathub'
Estado: 'Pendiente'
Resumen: 'Canal principal de Linux. Flatpak en Flathub (gratis) funciona en prácticamente todas las distribuciones, incluida Bazzite, que usa el usuario y es inmutable (ni .deb ni .rpm son allí el camino natural), y se actualiza solo. Incluye el ID io.github.* verificado con la cuenta de GitHub, el manifiesto, el metainfo de AppStream, el .desktop, los iconos y los permisos del sandbox: red, Secret Service, ~/.ssh, socket de ssh-agent y X11, porque AWT no tiene Wayland nativo. Hay que resolver cómo acepta Flathub una app Gradle, compilando desde fuente sin red o reempaquetando el binario del Release, y el agente Go. Revisión por PR a flathub/flathub.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §4. ID según [[Cambiar el identificador de la app a io.github]].'
Bloqueada:
  - "[[Licencia GPL-3.0-or-later del proyecto]]"
  - "[[Pipeline de release en GitHub Actions]]"
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-25T17:50:00+02:00
---

# Canal Linux Flatpak en Flathub

## Objetivo

`flatpak install flathub <id>` en cualquier distribución (en Bazzite, desde su
tienda de apps), con actualizaciones automáticas.

## Contexto

- El usuario usa **Bazzite** (Fedora Atomic): Flatpak es la vía de
  instalación nativa.
- Compose Desktop usa AWT, que **no tiene backend Wayland nativo** en los JDK
  estables: la app corre sobre XWayland, así que hace falta `--socket=x11`
  (`fallback-x11` no basta si la app no habla Wayland). Comprobar si el JDK usado
  ya trae un toolkit Wayland utilizable.
- El `SecretStore` de Linux usa Secret Service por D-Bus
  ([[Almacenamiento seguro de credenciales]]).
- Los diálogos de fichero de AWT/Swing **no** usan los portales de escritorio,
  así que importar claves necesita acceso al sistema de ficheros.

## Criterios de finalización

- **ID de la app** fijado según la regla de Flathub para `io.github.*` y
  verificado con la cuenta de GitHub del usuario.
- **Estrategia de build**, comprobando la política vigente de Flathub:
  - (a) compilar desde fuente sin red: dependencias de Gradle y módulos Go
    declarados como fuentes del manifiesto (generadores de flatpak-builder-tools
    o equivalentes), con el SDK y la extensión de OpenJDK de freedesktop. Es lo
    preferido, pero lo más costoso;
  - (b) reempaquetar el `tar.gz` del Release (permitido en algunos casos para
    binarios publicados por el propio autor).
  Anotar la decisión y el motivo aquí.
- **Permisos del sandbox** mínimos y justificados:
  - `--share=network`, `--socket=x11`, `--device=dri` y `--share=ipc`
  - `--talk-name=org.freedesktop.secrets`
  - `--socket=ssh-auth` (agente SSH)
  - `--filesystem=~/.ssh:ro` o el mínimo que requiera importar claves, dejando
    `known_hosts` y la configuración en los directorios de la app
  Probar que todo funciona dentro del sandbox: conexión, credenciales, nivel 3 y
  subida del agente.
- **Metainfo de AppStream** (`<id>.metainfo.xml`): descripción, capturas,
  `<releases>` por versión, `project_license` GPL-3.0-or-later, contenido OARS,
  URL del repositorio. Validar con `flatpak-builder-lint`/`appstreamcli`. Más el
  `.desktop` y los iconos hicolor ([[Icono y recursos gráficos de la app]]).
  Los PNG 16–512 ya salen en `branding/generated/png/`: aquí se instalan con el
  nombre del ID de la app. Las **capturas** de escritorio se hacen aquí, con la
  UI de ese momento.
- PR a `flathub/flathub` enviado y aceptado.
- Actualizaciones: tras cada Release, PR al repositorio de la app en Flathub,
  automatizado con `flatpak-external-data-checker` o un job del release.
- Probado en el **Bazzite** del usuario (y, si es posible, en otra distribución).

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
