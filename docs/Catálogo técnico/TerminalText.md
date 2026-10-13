---
Nombre: "TerminalText"
Tipo: "Servicio"
Área: "Terminal"
Feature: "Terminal"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/TerminalText.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.terminal"
Resumen: "Lectura de texto de un TerminalSnapshot para seleccionar y copiar, sin Compose. DocPos es una celda del documento (scrollback + pantalla) numerada desde la primera línea que el emulador conservó, así que no se mueve mientras la salida desplaza la pantalla ni cuando el tope del scrollback descarta líneas (TerminalSnapshot.droppedLines). wordAt da la palabra bajo una posición (corta en blancos, comillas, paréntesis, corchetes, llaves, < >, | ; y , y sigue las filas ajustadas, así que una ruta o URL larga sale entera); text une sin salto las filas ajustadas, quita los blancos del final de las demás, salta el relleno de los glifos anchos y copia como espacios las celdas invisibles (SGR 8); expand evita partir un glifo ancho; all selecciona del principio del scrollback a la última fila en uso."
Última modificación: 2026-10-13T09:40:00+02:00
---

# TerminalText

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[TerminalText.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/TerminalText.kt)
y sus pruebas en `TerminalTextTest`.

Notas de contrato:

- Las posiciones solo valen dentro del mismo `TerminalSnapshot.layoutEpoch` de
  [[TerminalEmulator]]: un reajuste, un scrollback borrado o una ocultación de
  líneas reescriben el documento, y quien guarde posiciones (la selección de
  [[TerminalView]]) las descarta.
- Lo usa [[TerminalView]] para la selección táctil y el menú de Copiar en
  Android.

Entregado en [[Seleccionar, copiar y pegar en la terminal de Android]].
