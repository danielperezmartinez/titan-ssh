---
Nombre: Verificar empaquetado del agente en APK Android
Estado: Pendiente
Resumen: 'Follow-up del nivel 3 (ADR-0008): verificar en un dispositivo Android real que los binarios del agente (recursos JVM /agent/ generados por el task buildAgentBinaries) viajan dentro del APK y AgentBinaries.load(target) los carga por classloader, igual que ya se verificó en escritorio (AgentBinariesResourceTest, chequeo ELF en el classpath). El mecanismo es idéntico (recurso Java por classloader; los tasks merge*JavaResource dependen de buildAgentBinaries), pero no se ejecutó on-device. Confirmar también que una sesión AGENT desde el móvil instala y conduce el agente contra el host de pruebas.'
Decisiones: 'Sigue [[ADR-0008 Diseño del agente de resiliencia nivel 3]]. Verifica el empaquetado hecho en [[titan-agent distribución multi-arch e instalación]] sobre el target Android. Prueba on-device como en [[ssh-test-host]] (pixel-9-pro-xl).'
Bloqueada: []
Fecha de creación: 2026-09-23T08:05:00+02:00
Última modificación: 2026-09-24T12:00:00+02:00
---

# Verificar empaquetado del agente en APK Android

## Objetivo

Confirmar en Android real lo que en escritorio ya está verde: que el binario del
agente se empaqueta como recurso y se carga en runtime, y que una sesión `AGENT`
funciona end-to-end desde el móvil.

## Contexto

El task Gradle `buildAgentBinaries` cross-compila el agente a
`build/generated/agentBinaries/agent/` y ese dir está cableado como resources de
`jvmSharedMain`; los tasks `merge*JavaResource` (Android) dependen de él, así que
el recurso debería quedar en el APK. `AgentBinaries.load(target)` lo lee por
`getResourceAsStream("/agent/titan-agent-<slug>")`. En escritorio está verificado
(`AgentBinariesResourceTest` con chequeo ELF); en Android **no** se ha ejecutado.

## Criterios de finalización (borrador)

- Construir el APK (`:androidApp:assembleDebug`) y confirmar que
  `/agent/titan-agent-linux-*` está dentro (p. ej. inspeccionar el APK, o un test
  instrumentado que haga `AgentBinaries.load(...)` y valide magia ELF).
- Prueba on-device ([[ssh-test-host]], pixel-9-pro-xl): una sesión con nivel
  "agente" contra `nocendland-petit` instala el binario, conecta por el agente y
  sobrevive a un corte con replay.
- Si el recurso NO viaja en el APK (empaquetado de resources KMP/AGP distinto al
  esperado), ajustar el cableado (srcDir/merge task) hasta que lo haga.

## Notas

- El binario del destino es el del **host remoto** (linux/darwin), no el del
  teléfono; el APK solo lo transporta para subirlo por SSH. No hace falta un
  binario android/arm del agente.
- Flujo de instalación del APK en el Pixel documentado en [[ssh-test-host]]
  (Tailscale `file cp` a la IP, no al nombre).
- **Cambio de destinos (ADR-0009)**: la lista de binarios empaquetados crecerá
  (incluidos Windows `.exe` y macOS) en
  [[Instalación del agente en destinos Windows y multi-SO]]. Si se verifica
  después de ese cambio, comprobar la lista nueva y el tamaño de la APK. Para la
  prueba en el dispositivo está instalada la skill `android-cli`.
- **Avance del 2026-09-24** (paso 1 de [[Seguimiento de tareas pendientes]]):
  tras un `clean`, `:shared:processAndroidMainJavaRes` leía
  `build/generated/agentBinaries` sin depender de `buildAgentBinaries` y
  Gradle abortaba la build. Se corrigió en `shared/build.gradle.kts` (commit
  `b528900`) y, con eso, la APK de debug lleva `agent/titan-agent-linux-amd64`,
  `-linux-arm64` y `-darwin-arm64` (comprobado con `unzip -l`). Quedan por
  comprobar la build de release y R8, y la prueba en el dispositivo.
