---
Nombre: "MousepadView"
Tipo: "Componente UI"
Área: "Terminal"
Feature: "Mouse pad"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ui/MousepadView.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.ui"
Resumen: "Pestaña de una sesión mouse pad (ADR-0016): el móvil como touchpad y teclado del destino. La superficie interpreta los gestos de un touchpad de portátil con pointerInput: un dedo mueve y un toque es clic izquierdo; dos dedos hacen scroll y un toque con dos es clic derecho (con tres, central); mantener un dedo quieto pulsa el botón izquierdo para arrastrar. Debajo, tres botones de ratón que se mantienen pulsados, Ctrl/Alt/Mayús/Win fijos para la siguiente tecla (Win solo, abre el menú Inicio), las teclas que le faltan al teclado del móvil (Esc, Tab, flechas, Supr, F1-F12, multimedia) y el teclado del sistema (reutiliza SoftKeyboardCapture de TerminalView); en escritorio, el teclado físico. La aceleración y el scroll los calcula MousepadMotion (puro, en dp, con restos entre eventos), y los atajos MousepadTyping. La franja de arriba dice si la entrada llega al escritorio o si está bloqueado. SessionsArea la elige en lugar de TerminalView cuando SessionTab.isMousepad."
Última modificación: 2026-10-01T21:00:00+02:00
---

# MousepadView

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[MousepadView.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/ui/MousepadView.kt)
y, para el cálculo del movimiento,
[MousepadMotion.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/MousepadMotion.kt)
(cubierto por `MousepadMotionTest`).

Envía las tramas por `SessionTab.sendInput`, que las ordena y las pasa a
[[InputTransport]]. Los ajustes de la sesión (`MousepadSettings`: velocidad
del puntero y scroll natural) vienen del [[Modelo de configuración]].

Piezas relacionadas:

- [[TerminalView]] — de donde reutiliza el aviso de clave de host, los botones
  accesorios y la captura del teclado del sistema.
- [[ADR-0016 Sesión mouse pad y ayudante de escritorio en Windows]].
