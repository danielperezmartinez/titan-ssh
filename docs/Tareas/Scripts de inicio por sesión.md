---
Nombre: Scripts de inicio por sesión
Estado: Pendiente
Resumen: 'Automatización clave del producto: formulario guiado por sesión para crear varios scripts (inicio, post-inicio, etc.) reordenables que se ejecutan al conectarse. UX y alcance v1 confirmados.'
Decisiones: Enmarcada en [[Arquitectura de dos áreas Configuración y Sesiones]].
Bloqueada: []
Fecha de creación: 2026-09-17T15:32:11+02:00
Última modificación: 2026-09-17T16:48:56+02:00
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

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
