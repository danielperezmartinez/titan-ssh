---
Nombre: "Tema y tokens visuales"
Tipo: "Token"
Área: "UI compartida"
Feature: "Shared UI"
Estado: "Vigente"
Ámbito: "Aplicación"
Fuente: "shared/src/commonMain/kotlin/im/gar/titanssh/theme/Color.kt"
Entrada pública: "im.gar.titanssh.theme"
Resumen: "Tokens visuales dark-first y su mapeo a Material 3. TitanColors (superficies Canvas/Surface/SurfaceElevated/TerminalBg, textos Ink/Body/Mute/Stone, HairlineStrong, y rampa semántica Accent/Success/Warning/Danger reservada a estado, no a decoración). TitanTheme envuelve MaterialTheme con darkColorScheme y una escala tipográfica mono con JetBrains Mono bundleada como recurso de Compose (fallback al monospace del sistema). Es la base visual de toda la UI compartida; no se introducen colores fuera del set acordado."
Última modificación: 2026-09-18T15:30:00+02:00
---

# Tema y tokens visuales

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[Color.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/theme/Color.kt)
(`TitanColors`) y
[Theme.kt](../../shared/src/commonMain/kotlin/im/gar/titanssh/theme/Theme.kt)
(`TitanTheme`, escala tipográfica y mapeo a Material 3). Las medidas
(`TitanDimens`: hairline, radios 4px/0px, espaciado, `TouchTarget`) viven en el
mismo paquete `im.gar.titanssh.theme`.

Reglas de uso:

- **Dark-first** intencionado: el esquema oscuro es la línea base del producto;
  un tema claro es tarea posterior y no está cableado.
- **Disciplina de color**: `Accent` solo para interacción/selección; la rampa
  success/warning/danger solo para estado real (p. ej. conexión viva), nunca como
  adorno.
- **Mono en todo** con marcadores ASCII entre corchetes como iconos.

Gobernado por las decisiones visuales
[[Tokens visuales dark-first base opencode]] y su ampliación
[[Vocabulario ASCII ampliado y disciplina de color]]. Lo consumen todos los
[[Componentes UI compartidos]].
