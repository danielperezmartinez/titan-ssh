---
Nombre: 'Canal Arch Linux AUR'
Estado: 'Pendiente'
Resumen: 'Paquete titan-ssh-bin en AUR (gratis) para Arch y derivadas, que el usuario usa. Un PKGBUILD descarga el tar.gz del GitHub Release, verifica su sha256 e instala la app con JRE embebido en /opt, un lanzador, el .desktop, los iconos y la licencia; libsecret o un proveedor de Secret Service van como optdepends. La cuenta de AUR la crea el usuario. La actualización del PKGBUILD en cada release se automatiza desde GitHub Actions con una clave SSH de AUR guardada como secreto.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §4.'
Bloqueada:
  - "[[Licencia GPL-3.0-or-later del proyecto]]"
  - "[[Pipeline de release en GitHub Actions]]"
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-23T22:50:00+02:00
---

# Canal Arch Linux AUR

## Objetivo

Instalar y actualizar titan-ssh en Arch con `yay`/`paru` como cualquier otro
paquete.

## Criterios de finalización

- **Cuenta de AUR creada por el usuario** (el agente no crea cuentas) con una
  clave SSH dedicada.
- `PKGBUILD` de `titan-ssh-bin`:
  - `source` = `tar.gz` del Release, `sha256sums` reales y `license=('GPL-3.0-or-later')`.
  - Instala en `/opt/titan-ssh`, crea el lanzador en `/usr/bin/titan-ssh`, el
    `.desktop` en `/usr/share/applications` y los iconos hicolor, y la licencia
    en `/usr/share/licenses`.
  - `depends` mínimos: el JRE va embebido; comprobar qué librerías del sistema
    necesita Skiko y AWT (`libx11`, `libxext`, `libxrender`, `libxtst`,
    `freetype2`, `fontconfig`…). `optdepends`: proveedor de Secret Service
    (`gnome-keyring` o `kwallet`) para el `SecretStore`
    ([[Almacenamiento seguro de credenciales]]).
  - `.SRCINFO` generado y `namcap` sin errores.
- El PKGBUILD vive en el repositorio (p. ej. `packaging/aur/`) como plantilla,
  y un job del release lo actualiza y lo publica en AUR (secreto con la clave
  SSH de AUR).
- Probado en el Arch del usuario: instalar, actualizar a la siguiente versión y
  desinstalar.
- Opcional, más adelante: `titan-ssh` compilado desde fuente.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
