---
Nombre: "KeyBar"
Tipo: "Modelo de dominio"
Área: "Terminal"
Feature: "Terminal"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/KeyBar.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.terminal"
Resumen: "Catálogo de las teclas que puede llevar la barra de encima del teclado, común a la terminal y al mouse pad. Cada KeyBarKey tiene un id estable (lo que guarda AppSettings.keyBar), una etiqueta, un grupo (KeyBarGroup: modificadores, control, navegación, atajos, funciones, símbolos, escritorio remoto, acciones) y una KeyBarAction: modificador pegajoso (bit InputKeys.MOD_*), tecla con nombre, texto, Ctrl+letra de un toque o Pegar. terminalBytes(mods) da los bytes para el PTY (vía TerminalKeys) y desktopFrames(mods) las tramas para el agente (vía MousepadTyping); inTerminal es falso para las teclas que solo tiene un escritorio remoto (Win, ImpPnt, Menú, multimedia). KeyBar.resolve(ids) convierte la lista guardada en teclas (null = defaultIds; ids desconocidos y repetidos se descartan). Puro, cubierto por KeyBarTest."
Última modificación: 2026-10-13T08:20:00+02:00
---

# KeyBar

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[KeyBar.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/KeyBar.kt);
su cobertura está en `KeyBarTest`.

- Los ids **no se cambian** una vez publicados: están en la configuración de
  los usuarios. Una tecla nueva se añade con un id nuevo; una que se retire
  desaparece sola de las barras guardadas (resolve la descarta).
- La pintan [[KeyBarView]] en [[TerminalView]] y [[MousepadView]]; cada vista
  decide qué hace la pulsación con terminalBytes o desktopFrames.
- La lista guardada vive en el [[Modelo de configuración]] (`AppSettings.keyBar`)
  y se cambia con [[ConfigController]] (`setKeyBar`).
- Nace con [[Barra de teclas configurable con arrastre]].
