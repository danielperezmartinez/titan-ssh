---
Nombre: 'Icono y recursos gráficos de la app'
Estado: 'Hecha'
Resumen: 'Icono propio en todas las plataformas desde una única fuente vectorial (branding/icon.svg): .ico multi-tamaño para el MSI, .png 512 para el .deb, icono de ventana, icono adaptativo con monocromo en Android, PNG 16–512 para hicolor, e icono de 512 y gráfico de 1024×500 para las tiendas. Lo genera todo branding/RenderIcon.java. Las capturas de pantalla se dejan para los canales públicos.'
Decisiones: 'Diseño en [[Icono de la app T que sobrevive al microcorte]]. Necesaria para [[ADR-0011 Distribución y canales de publicación]]. Las capturas de pantalla pasan a [[Canal Android IzzyOnDroid]] y [[Canal Linux Flatpak en Flathub]], porque la UI cambiará antes de la fase de distribución pública.'
Bloqueada: []
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-25T17:50:00+02:00
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

- `java branding/RenderIcon.java` regenera todas las salidas sin errores; revisados
  a ojo el PNG de 512, el de 32 y el gráfico de 1024×500 (sin costuras).
- `:desktopApp:compileKotlin` y `:androidApp:assembleDebug` pasan.
- Android: la APK de debug instalada en el emulador muestra el icono adaptativo
  en el cajón de aplicaciones.
- Escritorio: `:desktopApp:run` muestra el icono en la barra de título y en la
  barra de tareas de Windows.
- `:desktopApp:packageMsi` (con un JBR que trae jpackage) genera el MSI con el
  `.ico`. El `.deb` con el `.png` se comprobará en Linux en el paso 4.

## Resultado

- Diseño: [[Icono de la app T que sobrevive al microcorte]] (concepto C de
  tres bocetos).
- Fuente `branding/icon.svg` y generador `branding/RenderIcon.java`. Las salidas
  y dónde se usan se describen en la decisión visual.
- Conectado: `android:icon`/`roundIcon` en el manifiesto (adaptativo en
  `mipmap-anydpi`, sin raster porque `minSdk` es 26),
  `windows.iconFile`/`linux.iconFile` en `desktopApp/build.gradle.kts` y
  `icon` de la ventana en `main.kt`.
- **Fuera de esta tarea**: las capturas de Android y escritorio, que se harán
  con la UI definitiva en [[Canal Android IzzyOnDroid]] y
  [[Canal Linux Flatpak en Flathub]]. Los nombres del tema hicolor con el ID de
  la app (`<app-id>.png`) se fijan en el paso de Flatpak y AUR; aquí solo se
  generan los tamaños.
