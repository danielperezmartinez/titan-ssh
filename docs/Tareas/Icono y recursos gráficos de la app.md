---
Nombre: 'Icono y recursos gráficos de la app'
Estado: 'Pendiente'
Resumen: 'La app no tiene icono en ninguna plataforma (ni mipmap Android, ni .ico ni .png de escritorio). Todos los canales lo exigen: MSI (.ico), .deb/.rpm/AUR/Flatpak (.png 128/256/512 o .svg en hicolor), Android (icono adaptativo + monocromo) e IzzyOnDroid/Play (icono de 512 px, gráfico de 1024x500 y capturas). Diseñar el icono (registrando la decisión visual) y generar todos los tamaños desde una fuente vectorial.'
Decisiones: 'Necesaria para [[ADR-0011 Distribución y canales de publicación]]. El diseño se registra en el sistema de Decisiones visuales.'
Bloqueada: []
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-23T22:50:00+02:00
---

# Icono y recursos gráficos de la app

## Objetivo

Tener un icono propio y los recursos gráficos que piden los instaladores y las
tiendas, generados desde una única fuente.

## Criterios de finalización

- Decisión visual del icono registrada en `Decisiones visuales/` (con el
  usuario: concepto, colores y relación con el tema de la app). Recordar que
  titan-ssh no imita a Termius ([[README]]).
- Fuente vectorial (SVG) versionada en el repositorio.
- Salidas generadas:
  - Android: icono adaptativo (capas de primer plano y fondo) + monocromo
    (iconos temáticos de Android 13+), en `androidApp/src/main/res`.
  - Windows: `.ico` multi-tamaño (16–256), para `windows.iconFile`.
  - Linux: `.png` de 512 para `linux.iconFile`, más el tema hicolor
    (128/256/512 o `scalable/*.svg`) para Flatpak y AUR.
  - Ventana de escritorio: el icono de la ventana en `main.kt`.
  - Ficha pública: icono de 512×512, gráfico de 1024×500 y capturas de
    Android y escritorio (IzzyOnDroid, Flathub y, más adelante, Play).

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
