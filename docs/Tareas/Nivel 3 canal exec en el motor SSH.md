---
Nombre: Nivel 3 canal exec en el motor SSH
Estado: Hecha
Resumen: 'Subtarea del nivel 3 (ADR-0008): añadir soporte de canal exec al motor SSH. Hoy SshSession solo expone openShell(); el agente se lanza con `exec titan-agent --session <id>` y se habla el protocolo por tramas sobre el stdio de ese canal. Hace falta un SshExecChannel (stdin/stdout/stderr crudos, sin PTY) en SshSession/SshjSession sobre sshj, análogo a SshShell pero sin allocatePTY ni startShell.'
Decisiones: Sigue [[ADR-0008 Diseño del agente de resiliencia nivel 3]] (opción B) y [[ADR-0004 Librería SSH]] (sshj). Amplía el contrato de [[SshConnector]] / motor SSH.
Bloqueada: []
Fecha de creación: 2026-09-19T18:25:00+02:00
Última modificación: 2026-09-19T18:25:00+02:00
---

# Nivel 3: canal exec en el motor SSH

## Objetivo

Permitir que el cliente ejecute un comando remoto (`titan-agent --session <id>`)
y hable con él por el **stdio crudo** de ese canal exec, sin PTY. Es el transporte
que la opción B de [[ADR-0008 Diseño del agente de resiliencia nivel 3]] necesita.

## Criterios de finalización (borrador)

- `SshSession` gana un `exec(command): SshExecChannel` (nombre a afinar) que abre
  un canal exec de sshj sin `allocatePTY`, exponiendo `output: Flow<ByteArray>`
  (stdout), `send(bytes)` (stdin) y `close()`; stderr capturado aparte o mezclado
  según convenga al protocolo.
- Implementación en `SshjSession`/`SshjConnector` (`jvmShared`), reutilizando el
  patrón de lectura de `SshjShell` (goroutine/coroutine que bombea el
  `inputStream` a un `Channel`).
- Cobertura headless con un fake, y verificación real opcional vía el test
  integración opt-in contra el host de pruebas.

## Entregado

- `SshExecChannel` (contrato, `commonMain/ssh/SshSession.kt`): `output` (stdout),
  `errors` (stderr), `send(bytes)` (stdin), `close(): Int?` (exit status). Sin PTY.
- `SshSession.exec(command)` con implementación por defecto que lanza
  `NotImplementedError` (no rompe los fakes de test que solo hacen `openShell`).
- `SshjExecChannel` (`jvmShared/ssh/SshjConnector.kt`): sobre `Session.exec` de
  sshj, con lectores de stdout/stderr al patrón de `SshjShell`.
- Test de integración **opt-in** (`SshjIntegrationTest.exec_channel_runs_a_command_without_a_pty`):
  ejecuta `echo …; tty` y comprueba stdout + que **no hay PTY** (`not a tty`).
- Catálogo ampliado en [[SshConnector]] (misma superficie del motor SSH).

## Verificación

- Compila en ambos targets (`:shared:compileAndroidMain`,
  `:shared:compileKotlinDesktop`) y **suite de escritorio completa en verde**
  (17 suites, 104 tests, 0 fallos; el test de integración exec se salta sin
  credenciales).
- **Real contra host** ([[ssh-test-host]], `nocendland-petit`): `SshjIntegrationTest`
  `tests=3 skipped=0 failures=0` — `exec_channel_runs_a_command_without_a_pty`
  ejecutó `echo …; tty` y verificó stdout (`titan-exec-ok`) y **ausencia de PTY**
  (`not a tty`). El canal exec queda verificado de punta a punta.

## Notas

- No confundir con `openShell` (que sí asigna PTY y arranca un shell): el agente
  no quiere PTY en **su** canal; el PTY lo crea el propio agente en el destino.
