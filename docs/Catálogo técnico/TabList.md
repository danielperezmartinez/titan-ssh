---
Nombre: "TabList"
Tipo: "Modelo de dominio"
Área: "Terminal"
Feature: "Terminal"
Estado: "Vigente"
Ámbito: "Feature"
Fuente: "shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/TabList.kt"
Entrada pública: "io.github.danielperezmartinez.titanssh.terminal"
Resumen: "Modelo inmutable de orden y pestaña activa de la tira de pestañas: solo ids (order + activeId), sin sesión viva, para poder probar abrir/activar/cerrar/reordenar headless. add() activa (duplicado solo re-activa), remove() elige vecino a la derecha al cerrar la activa, move/moveLeft/moveRight reordenan manteniendo la activa. El SessionManager lo empareja con los SessionTab reales."
Última modificación: 2026-09-24T12:00:00+02:00
---

# TabList

Después de descubrir esta pieza en el catálogo, consulta como fuente de verdad
[TabList.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/TabList.kt);
su comportamiento está cubierto por `TabListTest`.

Es la lógica pura de pestañas separada del runtime: la parte con estado (conexión,
shell, emulador) vive en [[SessionManager]] (`SessionTab`). Entregado en
[[Terminal multipestaña con sesiones simultáneas]].
