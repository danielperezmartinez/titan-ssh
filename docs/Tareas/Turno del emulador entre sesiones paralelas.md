---
Nombre: 'Turno del emulador entre sesiones paralelas'
Estado: Hecha
Resumen: 'Varias sesiones de agente, cada una en su worktree, prueban a la vez en el mismo emulador de Android y se pisan: la última instalación borra la build de las demás y los toques de adb acaban en la app de otra sesión. Se resuelve con un sufijo de paquete automático por worktree en las builds de debug (las apps conviven instaladas) y un turno (tools/emulator-lock) que se toma solo mientras se maneja la pantalla.'
Decisiones: 'El aislamiento de la instalación va primero, para que el turno solo cubra minutos (instalar, tocar, capturas) y no el rato en que el usuario prueba la app. El sufijo sale del nombre de la carpeta del worktree enlazado; el árbol principal y las builds de release no llevan sufijo. El candado es una carpeta creada con mkdir fuera del repositorio, una por ANDROID_SERIAL, con titular (raíz del worktree), renovación al volver a tomarlo y abandono a los 20 minutos. La norma es para cualquier agente (paso 0 de la regla 4 del README); cómo espera cada agente en segundo plano no va a la bóveda. Varios emuladores a la vez (-read-only) quedan como opción, no como norma.'
Bloqueada: []
Fecha de creación: 2026-10-13T20:40:00+02:00
Última modificación: 2026-10-13T21:02:00+02:00
---

# Turno del emulador entre sesiones paralelas

## Objetivo

Que varias sesiones en paralelo puedan probar en el emulador `Pixel_9_Pro_XL`
sin estropearse el trabajo entre ellas, y que una sesión que lo encuentre
ocupado espere sin bloquear a nadie.

## Criterios de finalización

- Una build de debug desde un worktree enlazado se instala con su propio
  paquete y etiqueta, sin tocar la de otras sesiones.
- El árbol principal y las builds de release conservan el paquete normal.
- `tools/emulator-lock/emulator-lock.sh` toma, renueva, espera y suelta el
  turno, y recupera un turno abandonado.
- La norma está en el paso 0 de la regla 4 del [[README]].

## Verificación

- Manifiesto fusionado: debug sin sufijo → `io.github.danielperezmartinez.titanssh`,
  "titan-ssh"; con `-PtitanDevSuffix=1-Config.List` →
  `…titanssh.w1_config_list`, "titan-ssh w1_config_list"; release con
  sufijo pedido → paquete normal. En un worktree enlazado `.git` es un fichero
  y en el árbol principal una carpeta, que es lo que usa la detección.
- Script, en una carpeta temporal: libre → tomar → renovar → ocupado para otro
  titular → `--wait --timeout` agota el tiempo (salida 3) → `release` ajeno
  rechazado → un `--wait` en segundo plano lo consigue al soltarlo →
  recuperación de un turno abandonado.
- Emulador: con el turno tomado se instaló una build con sufijo `locktest` al
  lado de las existentes; arrancó y el cajón de apps muestra
  "titan-ssh locktest" junto a "titan-ssh". Después se desinstaló.

## Resultado

- `androidApp/build.gradle.kts`: sufijo de debug por worktree
  (`-PtitanDevSuffix` para fijarlo o quitarlo) y etiqueta del lanzador con
  `manifestPlaceholders`.
- `tools/emulator-lock/`: script y `README.md` con el uso.
- [[README]], regla 4, paso 0: nadie maneja el emulador sin el turno.
