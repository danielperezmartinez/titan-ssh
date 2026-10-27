---
Nombre: 'Plugins como piezas incluidas que se activan'
Número: 32
Estado: 'Aceptada'
Resumen: 'La app tendrá una sección de plugins, pero un plugin no es código que se descarga: es una pieza que ya viene dentro de la app y que el usuario activa o desactiva. Activarlo puede desplegar en el destino lo que necesite (p. ej. hooks que llaman al agente). Se descarta descargar código ejecutable por seguridad (en la JVM no se puede aislar un plugin de las credenciales) y por las restricciones de Android, Google Play e IzzyOnDroid.'
Decisión: 'Los plugins son piezas incluidas en la app que el usuario activa; nunca se descarga ni se carga código ejecutable de fuera de la app.'
Consecuencias: 'Un plugin nuevo o su arreglo llega con una versión de la app, por el actualizador, y pasa por la misma revisión, firma y pruebas que el resto. Los plugins no pueden ser de terceros fuera del repositorio. "Instalar" un plugin es activarlo y, si lo necesita, desplegar sus piezas en el destino (scripts, hooks o configuración, nunca un binario distinto del agente). El sistema de plugins debe poder desactivar un plugin y retirar del destino lo que desplegó.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-27T22:20:00+01:00
Última modificación: 2026-10-27T22:20:00+01:00
---

# ADR-0032 · Plugins como piezas incluidas que se activan

## Contexto

El usuario quiere una sección de plugins en la app, empezando por dos:
[[Plugin de CLIs de agentes de IA]] y [[Plugin de MCP para titan-ssh]]. El
sistema que los sostiene es [[Sistema de plugins]]. La pregunta previa a
todo lo demás es qué es un plugin: código que se descarga e instala, o una
pieza que ya trae la app y se activa.

- La seguridad es un pilar del producto
  ([[ADR-0001 Credenciales en almacén nativo del SO]]). En la JVM no hay
  forma de aislar código cargado en el proceso (el `SecurityManager` ya no
  existe): un plugin descargado podría leer cualquier credencial.
- Android restringe la carga de código descargado; Google Play la prohíbe e
  IzzyOnDroid la marca como antifunción
  ([[ADR-0011 Distribución y canales de publicación]]).
- La app ya se actualiza sola con versiones firmadas
  ([[ADR-0025 Actualización automática de la app]]).

## Decisión

Un plugin es una pieza incluida en la app que el usuario activa o
desactiva desde la sección de plugins. No se descarga ni se carga código
ejecutable de fuera de la app. Activar un plugin puede desplegar en el
destino lo que necesite (scripts, hooks o configuración que hablan con el
agente), y desactivarlo lo retira.

## Alternativas consideradas

- Plugins descargables (JAR en escritorio, DEX en Android) — descartada: no
  se pueden aislar de las credenciales y choca con las tiendas de Android.
- Plugins declarativos descargables (solo datos: reglas, scripts del
  destino) — descartada por ahora: añade un canal de distribución y de
  firma propio sin un caso que lo pida; se puede replantear con una ADR
  nueva.

## Consecuencias

- Positivas: los plugins pasan por la misma revisión, firma, pruebas y
  actualizador que el resto de la app; sin superficie de ataque nueva por
  descarga de código; funciona igual en Android y escritorio.
- Negativas / compromisos: un plugin nuevo o su arreglo espera a una
  versión de la app; no hay plugins de terceros fuera del repositorio; los
  plugins desactivados también ocupan sitio en la app.
