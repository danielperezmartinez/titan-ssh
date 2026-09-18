---
Nombre: "Revisar formateo de la terminal"
Estado: Hecha
Resumen: 'La salida de comandos no aprovechaba el ancho: `ls -la` se envolvía pronto dejando ~media pantalla vacía. Causa (confirmada con lector en pantalla: cw=34 vs render real ~19): el ancho de celda se medía con una sonda estática, que se mide una sola vez en el primer frame con el fallback proporcional y no se re-dispara al cargar la fuente bundleada; el grid, en cambio, renderiza en JetBrains Mono estrecha. Arreglado midiendo el ancho de celda desde las líneas reales que el grid dibuja (onTextLayout, mismo pipeline), ignorando líneas recortadas. Verificado en dispositivo.'
Decisiones: Ajuste de [[Terminal multipestaña con sesiones simultáneas]].
Bloqueada: []
Fecha de creación: 2026-09-18T21:36:00+02:00
Última modificación: 2026-09-19T00:45:00+02:00
---

He probado a escribir ls -la y aparece todo como desordenado. Creo que lo que pasa es que el "contenedor" dónde aparece el texto no está aprovechando el máximo del ancho de la pantalla, entonces hay saltos de línea no deseados. Entiendo que saltos de líneas tienen que haber, pero hay media pantalla desaprovechada.

Adjunto captura (local, no versionada por posible dato de host): `Screenshot_20260918-213655.png`.

## Nota de implementación

Probable causa en `TerminalView.kt`: el número de columnas se calcula dividiendo el
ancho del `Box` entre el ancho de celda medido (`cellWidthPx`). Si `cellWidthPx` se
sobreestima (medida de la fuente/tamaño) o el área de medida no es la real, salen
menos columnas de las que caben → `resize(cols, rows)` pide un PTY estrecho y el
remoto envuelve pronto. Revisar la medición de la celda mono y que el `onSizeChanged`
use el ancho real disponible (padding incluido).

## Causa raíz (confirmada con lector en pantalla)

Se añadió temporalmente un lector de diagnóstico en el terminal que mostró:
`cols=38 rows=26 cw=34 pw=1344 snap=38`. Es decir, con panel de 1344 px la celda se
medía en **34 px** (→ 38 columnas, que llegaban bien al emulador y al PTY: `snap=38`),
pero el texto se **renderiza** en JetBrains Mono a ~**19 px/celda** (las columnas de
`ls -la` alinean, es monospace estrecho). El fallo era puramente la **medida**, no la
propagación de `resize`.

El desajuste: la celda se medía con una **sonda estática** (un `Text "MMMMMMMMMM"`),
que Compose **mide una sola vez en el primer frame** — cuando la fuente bundleada aún
no ha cargado y el resolver devuelve el **fallback proporcional** (Roboto, la `M` es
de las glifas más anchas) — y **no se vuelve a disparar** porque su contenido no
cambia. El grid, en cambio, re-hace layout con cada snapshot y por eso sí pinta con
la fuente ya cargada (estrecha). (Nota: `rememberTextMeasurer` tiene además caché de
layout interna; envolverlo en `derivedStateOf` tampoco bastó en un intento previo.)

## Resultado (2026-09-19)

En `TerminalView.kt`, la medida de celda pasa a derivarse de las **líneas que el
grid dibuja de verdad**:

- `TerminalGrid` reporta por **`onTextLayout`** de cada fila su
  `(ancho, nº de celdas, alto)`; el consumidor calcula `cellWidthPx = ancho/celdas`.
  Al ser las mismas `Text` que se renderizan (mismo font/size/pipeline) y re-hacerse
  layout con cada snapshot, la medida refleja siempre la fuente ya cargada.
- Se **ignoran las líneas recortadas** (`ancho >= ancho usable`), para que una línea
  que llena el ancho no subestime el avance.
- Las **columnas/filas se derivan del tamaño del panel** (`paneSize`) + las métricas
  de celda, no dentro de `onSizeChanged`; así, cuando la medida de celda cambia
  (carga de fuente), se recomponen y se re-emite `resize`.
- Se **descuenta el padding horizontal** del grid (`SpaceXs` por lado) del ancho
  usable.

El lector de diagnóstico era temporal y **se ha retirado**.

## Verificación

- Build OK (`JAVA_HOME` al JBR, wrapper, `--console=plain`):
  `:shared:compileKotlinDesktop` + `:shared:compileAndroidMain` +
  `:androidApp:assembleDebug` → `BUILD SUCCESSFUL` (solo warnings preexistentes de
  `LocalClipboardManager`).
- **Intento 1** (medir con `derivedStateOf` sobre `TextMeasurer`) y **intento 2**
  (sonda `Text` estática con `onTextLayout`): **no funcionaron** en dispositivo —
  misma salida encogida. El lector reveló `cw=34` estable (fallback), lo que llevó a
  medir desde las líneas reales del grid.
- **Confirmado en dispositivo (2026-09-19, usuario)**: «ahora sí», `ls -la` aprovecha
  todo el ancho. Cerrada.
