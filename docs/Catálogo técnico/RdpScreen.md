---
Nombre: 'RdpScreen'
Tipo: 'Servicio'
Área: 'Conexión SSH'
Feature: 'Escritorio remoto'
Estado: 'En revisión'
Ámbito: 'Feature'
Fuente: 'shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/rdp/RdpScreen.kt'
Entrada pública: 'io.github.danielperezmartinez.titanssh.rdp'
Resumen: 'El escritorio remoto de una pestaña RDP (ADR-0028). run(engine, canal, params, certificados, clave) conecta el motor nativo (RdpEngine, IronRDP vía JNI en RdpNative/JniRdpEngine de jvmShared, cargado con loadRdpEngine) a un SshDirectChannel con dos hilos de bombeo y un bucle de eventos, y devuelve cómo terminó (RdpEvent.Ended: closed, server, auth, certificate, negotiation, transport o error). Expone frame (píxeles del escritorio y versión, para RdpFrameImage) y certificate (PendingRdpCertificate: huella SHA-256 que el usuario acepta o rechaza antes de enviar credenciales, guardada en RdpCertificateStore junto a known_hosts). Entrada: mouseMove/moveBy, mouseButton, wheel, key (scancode set 1), text (letras y dígitos como teclas, el resto Unicode) y send(AgentFrame) para reutilizar la entrada del mouse pad (RdpKeys). En revisión: prueba de concepto, aún sin probar con un Windows real.'
Última modificación: 2026-10-21T12:30:00+02:00
---

# RdpScreen

Fuente de verdad: [RdpScreen.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/rdp/RdpScreen.kt)
y, para el motor, [RdpEngine.kt](../../shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/rdp/RdpEngine.kt),
[RdpNative.kt](../../shared/src/jvmSharedMain/kotlin/io/github/danielperezmartinez/titanssh/rdp/RdpNative.kt)
y el crate [`rdp/`](../../rdp/src/lib.rs).

- La decisión y sus límites: [[ADR-0028 Cliente RDP embebido con IronRDP por un túnel SSH]].
- La usa [[SessionManager]] a través de `SessionTab` (tipo de sesión RDP) y la
  pinta `RdpView`, que reutiliza los gestos, la barra de teclas y la captura
  del teclado del sistema de [[MousepadView]].
- Transporte: `SshSession.openDirect` de [[SshConnector]].
- Tests: `RdpModelTest` y el opt-in `RdpIntegrationTest` contra
  `tools/test-rdp/`.
