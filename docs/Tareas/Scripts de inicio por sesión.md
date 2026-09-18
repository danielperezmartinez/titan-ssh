---
Nombre: Scripts de inicio por sesión
Estado: En curso
Resumen: 'Automatización clave. Modelo y formulario guiado ENTREGADOS dentro del editor de sesión (crear/editar/habilitar/reordenar scripts con todos los atributos v1: fase, comportamiento, expect, reconnect, secretos por ref, snippets como scripts bajo demanda). Falta la EJECUCIÓN al conectar/reconectar, que depende del flujo de lanzamiento del terminal.'
Decisiones: Enmarcada en [[Arquitectura de dos áreas Configuración y Sesiones]]; el modelo se formaliza en [[ADR-0007 Modelo y persistencia de configuración]] y consume [[ADR-0001 Credenciales en almacén nativo del SO]].
Bloqueada: [[Terminal multipestaña con sesiones simultáneas]]
Fecha de creación: 2026-09-17T15:32:11+02:00
Última modificación: 2026-09-18T14:10:00+02:00
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

**Pendiente (ejecución):** ejecutar los scripts habilitados **en orden según su
fase al conectar**, y respetar `ReconnectBehavior` al reconectar. Se apoya en el
`SshShell` del [[Motor de conexión SSH]] (ya disponible) pero necesita el flujo de
lanzamiento de sesión que aporta [[Terminal multipestaña con sesiones simultáneas]];
por eso la tarea queda **bloqueada por** ella y en `En curso`.

## Verificación

Autoría/modelo: cubierto por los tests de [[Panel de gestión de hosts y sesiones]]
(`ConfigModelTest`, `JsonFileConfigStoreTest`) y el build de ambos targets. La
ejecución se verificará contra un host real al completar la parte pendiente.

## Resultado

<Se completará al cerrar la ejecución de scripts en el flujo de lanzamiento.>
