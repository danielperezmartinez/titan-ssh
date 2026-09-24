---
Nombre: 'Entorno de pruebas automático multiplataforma'
Número: 12
Estado: 'Rechazada'
Resumen: 'Se estudió montar un entorno que probara solo, sin intervención del usuario, las conexiones cruzadas entre clientes y destinos (Linux → Windows, Android → Linux/Windows, Windows → Linux) y los microcortes. El usuario exigió que no dependiera de su máquina, de su red ni de su tailnet, y la única opción que lo cumple (un runner de GitHub con una VM Windows, contenedores Linux y el emulador de Android dentro) resultó demasiado compleja y poco eficiente. Rechazada el 2026-09-24: se sigue probando a mano, y cuando una prueba necesite al usuario (destino Windows, dispositivo real) se le pide.'
Decisión: 'No montar un entorno automático de pruebas cruzadas. Se mantienen los tests headless, los tests de integración opt-in (se saltan sin datos por -P) y las pruebas manuales o semiautomáticas con ayuda del usuario cuando haga falta.'
Consecuencias: 'Sin infraestructura extra que mantener ni tiempo de CI. A cambio, las pruebas contra Windows y en dispositivos reales requieren al usuario y no se repiten solas; cada tarea documenta en su Verificación qué se probó y cómo.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-24T15:00:00+02:00
Última modificación: 2026-09-24T15:00:00+02:00
---

# ADR-0012 · Entorno de pruebas automático multiplataforma

## Contexto

El usuario planteó (2026-09-24) un entorno donde el agente pudiera probar sin
su intervención las conexiones entre plataformas: de Linux a Windows, de
Android a Linux y a Windows, y de Windows a Linux. Hasta ahora las pruebas
reales se hacen contra un host Linux del usuario, el `sshd` de Windows de su
PC y el emulador o el móvil, con su ayuda cuando hace falta.

Requisito del usuario: debe funcionar **independientemente de su máquina** y
sin tocar **su red ni su tailnet**. El repositorio es público (regla 5 de
[[README]]), así que nada de su entorno puede acabar en el repositorio ni en
CI.

## Alternativas consideradas

1. **Entorno local en el PC de desarrollo** (contenedores Docker con `sshd`,
   Toxiproxy para los microcortes, el `sshd` de Windows con un usuario de
   pruebas fijo y el emulador). Descartada: depende de esa máquina.
2. **GitHub Actions con runners Linux y Windows unidos por Tailscale.**
   Descartada: usa la tailnet del usuario.
3. **Un solo runner Ubuntu de GitHub con todo dentro**: contenedores Linux,
   una VM Windows de evaluación con KVM (destino y cliente) y el emulador de
   Android, en una red privada del runner. Cumple el requisito, pero es
   demasiado lío y poco eficiente (instalar Windows en cada ejecución, de 25 a
   40 minutos estimados, y dudas sobre la estabilidad con la VM y el emulador a
   la vez). Rechazada por el usuario.

## Decisión

No se monta. Se sigue con:

- Tests headless (`:shared:desktopTest`, `go test`).
- Tests de integración opt-in, con los datos del entorno pasados por `-P` en
  tiempo de ejecución (nunca en el repositorio).
- Pruebas manuales o semiautomáticas (emulador por adb, `sshd` local) y, cuando
  la prueba necesite al usuario (crear un usuario de Windows, el Pixel real), se
  le pide.

## Consecuencias

- Cero infraestructura adicional y cero minutos de CI por esto.
- Las pruebas cruzadas no se repiten solas: cada tarea deja en su
  **Verificación** qué combinación se probó y cómo.
- Afecta al método de prueba de [[titan-agent daemon en Windows]], que queda
  así resuelto: prueba manual con el usuario, con la opción ya probada del
  `sshd` local y un usuario estándar temporal.
