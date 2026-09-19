---
Nombre: Scripts de inicio por sesión
Estado: Hecha
Resumen: 'Automatización clave ENTREGADA y verificada. Modelo y formulario guiado en el editor de sesión; EJECUCIÓN AL CONECTAR: runner que corre el `cd` inicial y los scripts habilitados por fase (ON_SHELL_START → POST_INIT) sobre la shell viva, con ${VAR}/secretos por ref, envVars, delay, expect, esperar-a-terminar (centinela con $?/timeout) y continuar/abortar. Verificado headless (ScriptRunnerTest 10/10) y de punta a punta contra host real (nocendland-petit: cd /tmp + script imprime PWD=/tmp). La ejecución al RECONECTAR queda para [[Resiliencia de sesión ante microcortes de red]] (fase ON_RECONNECT); PRE_CONNECT_LOCAL diferida (sin ejecutor local); silent no suprimible sobre PTY compartida.'
Decisiones: Enmarcada en [[Arquitectura de dos áreas Configuración y Sesiones]]; el modelo se formaliza en [[ADR-0007 Modelo y persistencia de configuración]] y consume [[ADR-0001 Credenciales en almacén nativo del SO]]. La ejecución al reconectar y las fases ON_RECONNECT quedan para [[Resiliencia de sesión ante microcortes de red]]; PRE_CONNECT_LOCAL diferida (sin ejecutor local aún). Superficie catalogada en [[ScriptRunner]].
Bloqueada: []
Fecha de creación: 2026-09-17T15:32:11+02:00
Última modificación: 2026-09-19T16:45:00+02:00
---

# Scripts de inicio por sesión

## Objetivo

Replicar de forma gratuita la automatización que Termius solo ofrece en su
versión de pago: al entrar en una sesión, ejecutar automáticamente comandos de
configuración (acceder al directorio de trabajo del proyecto, arrancar
herramientas o servicios como Claude, etc.). Es una funcionalidad clave del
producto.

## UX confirmada

- Dentro de una sesión (área de Configuración), un **formulario guiado** permite
  crear **varios scripts** y clasificarlos por fase (inicio, post-inicio, etc.).
- Los scripts se pueden **reordenar** fácilmente; se ejecutan en orden al conectar.

## Atributos configurables por script (alcance v1, confirmado)

- **Identidad y orden:** nombre/etiqueta, habilitado (on/off), orden (arrastrar),
  icono/color opcional.
- **Fase (cuándo se ejecuta):** pre-conexión (local), al abrir la shell (inicio),
  post-inicio (prompt listo), al reconectar tras microcorte (re-ejecutar todo /
  solo restaurar `cd` / no re-ejecutar), y bajo demanda (snippet manual con botón
  en la sesión).
- **Comportamiento:** visible en terminal vs silencioso; esperar a que termine
  (secuencial, con timeout) o disparar y seguir; si falla continuar o abortar la
  cadena; retardo opcional antes de ejecutar; esperar un patrón antes de enviar
  (expect "password:").
- **Datos y seguridad:** directorio de trabajo inicial (`cd`) como campo de
  primera clase de la sesión; variables/placeholders `${VAR}` (de la sesión/host
  o preguntadas al lanzar); inyección de secretos desde el `SecretStore` (nunca
  texto plano, ver [[ADR-0001 Credenciales en almacén nativo del SO]]); variables
  de entorno a exportar.

## Criterios de finalización

- Formulario guiado que permite crear, editar, habilitar/deshabilitar y
  **reordenar** varios scripts por sesión.
- Todos los atributos del alcance v1 anterior están soportados.
- Al conectar, los scripts habilitados se ejecutan en orden según su fase.
- El comportamiento al reconectar respeta la opción elegida (re-ejecutar /
  solo `cd` / no re-ejecutar), coherente con
  [[Resiliencia de sesión ante microcortes de red]].
- Los scripts "bajo demanda" son los **snippets** (ver
  [[Panel de gestión de hosts y sesiones]]).
- Ningún secreto viaja ni se guarda en texto plano.

## Estado del trabajo (2026-09-18)

Entregado junto con [[Panel de gestión de hosts y sesiones]] (para no duplicar:
los scripts viven dentro del editor de sesión). Ver [[ADR-0007 Modelo y
persistencia de configuración]].

**Hecho (autoría y modelo):**

- Modelo `@Serializable` `SessionScript` con **todos los atributos v1**: identidad
  y orden (etiqueta, habilitado, orden por posición en la lista), **fase**
  (`ScriptPhase`: pre-conexión local, al abrir shell, post-inicio, al reconectar,
  bajo demanda), **comportamiento** (`ScriptBehavior`: silencioso vs visible,
  esperar a terminar/timeout, continuar o abortar si falla, retardo, `expect`
  patrón), `ReconnectBehavior` (re-ejecutar / solo `cd` / nada), envVars y
  **secretos por referencia** (nunca texto plano), `cd` inicial como campo de
  primera clase de la sesión.
- **Formulario guiado** en el editor de sesión: crear, editar, habilitar/
  deshabilitar y **reordenar** (`[^]`/`[v]`) varios scripts; insertar un
  **snippet** de la biblioteca (los "bajo demanda", compartidos con
  [[Panel de gestión de hosts y sesiones]]).
- Persistencia verificada (round-trip JSON con scripts, ver la verificación de la
  tarea del panel) y build de ambos targets OK.

## Estado del trabajo (2026-09-19): ejecución al conectar

Desbloqueada: [[Terminal multipestaña con sesiones simultáneas]] ya está `Hecha`,
así que el flujo de lanzamiento existe. Implementada la **ejecución al conectar**.

**Hecho (ejecución):**

- `ScriptRunner` (commonMain, `terminal`): motor puro que, sobre la shell viva,
  envía el `cd` inicial y luego los scripts en el orden recibido, honrando todos
  los atributos v1 salvo `silent`: `${ref}` (sustitución **solo** de secretos del
  `SecretStore`; cualquier otro `${...}` se deja para que lo expanda la shell
  remota), `export` de `envVars` antes del cuerpo, `delaySeconds`, `expectPattern`
  (espera el patrón antes de enviar), `waitForCompletion` + `timeoutSeconds` (envía
  un centinela `printf` que arrastra `$?` y espera su eco) y `onFailure`
  (CONTINUE / ABORT la cadena). Devuelve un `ScriptOutcome` por unidad.
- `StartScriptAutomation` + seam `ShellAutomation`/`ShellIo`: selecciona las fases
  de conexión (`ON_SHELL_START` → `POST_INIT`, habilitadas, en orden) y resuelve
  los secretos del `SecretStore` solo en tiempo de ejecución (ADR-0001). Cableado
  en `AppShell` → `SessionManager`.
- `SessionTab` reestructurado: el `output` de sshj es de **un solo consumidor**
  (`receiveAsFlow`), que el pintor ya drena; ahora el pump difunde además una
  copia decodificada por un tee (`SharedFlow`, `tryEmit`, DROP_OLDEST) y el runner
  corre **en paralelo** al pintado para poder esperar prompts/centinela sin robarle
  bytes al emulador.
- Tests headless: `ScriptRunnerTest` (10/10) con un shell falso que hace de host
  para los centinela; cubren orden+cd, fases, sustitución de secretos, secreto
  ausente (skip), export de env, fire-and-forget, ABORT/CONTINUE, expect y la
  selección de fases de `StartScriptAutomation`. Compila `:shared` en desktop y
  android.

**Alcance de esta pasada (acordado):** solo **al conectar**. Quedan fuera:
`PRE_CONNECT_LOCAL` (no hay ejecutor local multiplataforma) y `ON_RECONNECT` /
`ReconnectBehavior` (dependen de [[Resiliencia de sesión ante microcortes de red]]).
`ON_DEMAND` son los snippets manuales.

**Limitación conocida:** `silent` no es aplicable sobre una PTY compartida (la
remota hace eco de la entrada); se acepta pero aún no se suprime. Los comandos con
`waitForCompletion` deben volver al prompt (no interactivos de larga duración).

## Verificación

- Autoría/modelo: `ConfigModelTest`, `JsonFileConfigStoreTest` (de
  [[Panel de gestión de hosts y sesiones]]).
- Ejecución (headless): `ScriptRunnerTest` 10/10 verde.
- **Punta a punta (host real):** `SessionTabIntegrationTest
  .start_scripts_run_on_connect_with_initial_cd` (opt-in) conecta a
  `nocendland-petit`, hace `cd /tmp` y comprueba que el script imprime `PWD=/tmp`
  (que solo puede venir de ejecución real: el eco del comando muestra el literal
  `$(pwd)`). **Verde** el 2026-09-19.
- Nota: en esa misma clase, el test previo `tab_connects_paints_output_and_accepts_input`
  (entrada por teclado, de [[Terminal multipestaña con sesiones simultáneas]]) falla,
  pero es **pre-existente e independiente**: reproduce igual sobre el código original
  (mis cambios stasheados) y no cubre esta tarea. Seguimiento aparte.

## Resultado

Ejecución de scripts al conectar entregada y verificada (headless + host real).
Runner completo: cd inicial, fases ON_SHELL_START/POST_INIT en orden, `${ref}` de
secretos, envVars, delay, expect, esperar-a-terminar con `$?`/timeout y
continuar/abortar. Reconexión y `ON_RECONNECT` (fase + `ReconnectBehavior`, vía
`ShellAutomation.onReconnected`) **ya entregados** en
[[Resiliencia de sesión ante microcortes de red]] (nivel 1).
