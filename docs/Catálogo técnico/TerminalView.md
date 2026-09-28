---
Nombre: "TerminalView"
Tipo: "Componente UI"
Área: "Terminal"
Feature: "Terminal"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ui/TerminalView.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.ui"
Resumen: "Composable que pinta un TerminalSnapshot con JetBrains Mono sobre un Canvas (TerminalCanvas): cada glifo en la posición exacta de su celda, capa de fondos, cursor de bloque y caché por fila, leyendo el snapshot solo en la fase de dibujo para no recomponer con cada salida. Mide la celda mono tras precargar la fuente, deriva columnas/filas del tamaño del panel y redimensiona el PTY cuando el tamaño se estabiliza (150 ms), colocando mientras tanto las filas donde las dejará el resize para que el contenido siga al teclado sin saltos. Permite desplazarse por el scrollback arrastrando o con la rueda (escribir vuelve abajo), y captura entrada por eventos de teclado (onPreviewKeyEvent: teclados físicos y escritorio) traducida con TerminalKeys hacia SessionTab.sendBytes. En Android añade la barra de teclas accesorias, pegar desde el portapapeles y un campo oculto que levanta el teclado software. La franja de estado muestra la fase, el nivel de resiliencia efectivo y, debajo, el aviso del nivel 3 (motivo de la degradación o aviso de systemd con la acción activar linger), que se puede cerrar con [x]. Mientras la pestaña reconecta, o si está caída o fallida, la franja ofrece reconectar (SessionTab.reconnectNow). Si recibe scripts (los bajo demanda de la sesión y la biblioteca, ADR-0013), la franja muestra [>] scripts mientras la pestaña está conectada y abre un menú que los envía con SessionTab.runScript. Si la sesión tiene túneles activados, la franja muestra [=] túneles activos/total (en color de aviso si alguno falla) y abre un panel con el estado de cada uno y el motivo del fallo. Dark-first, sin chrome de Material."
Última modificación: 2026-09-28T11:05:00+02:00
---

# TerminalView

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[TerminalView.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ui/TerminalView.kt).

Colaboradores:

- Pinta el `TerminalSnapshot` de [[TerminalEmulator]] con la paleta `AnsiPalette`.
- Convierte teclado y barra accesoria en bytes con [[TerminalKeys]] y los envía a
  la pestaña de [[SessionManager]] (`SessionTab.sendBytes`).
- Construida con [[Tema y tokens visuales]] / [[Componentes UI compartidos]] y
  regida por [[Vocabulario ASCII ampliado y disciplina de color]].

Nota: la entrada por teclado software en Android (campo ancla) es compile-verified
y necesita comprobación en dispositivo; ver
[[Terminal multipestaña con sesiones simultáneas]].

El pintado celda a celda sobre Canvas ([TerminalCanvas.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ui/TerminalCanvas.kt)),
el redimensionado con espera y el scroll llegan con
[[Terminal fluida con ajuste de líneas y sin rastro de la automatización]].
