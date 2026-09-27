---
Nombre: 'Biblioteca de scripts unificada con los snippets'
Número: 13
Estado: 'Aceptada'
Resumen: 'Los snippets y los scripts de biblioteca pasan a ser una sola entidad, LibraryScript, con su pestaña Scripts en Configuración, que sustituye a Snippets. La biblioteca guarda lo reutilizable del script (nombre, comando, etiquetas, comportamiento, expect, variables y secretos). La sesión guarda cómo lo usa (fase, orden, activado y comportamiento al reconectar) y puede mezclar scripts de biblioteca, enlazados por id, con scripts propios de un solo uso. Borrar un script de biblioteca en uso deja una copia propia en cada sesión. Los túneles quedan fuera: aún no se ejecutan.'
Decisión: 'Una sola biblioteca de scripts (LibraryScript en TitanConfig.scripts), referenciada desde SessionScript.libraryScriptId y materializada al resolver la sesión. Config versión 2, con migración automática de la versión 1 que nunca cambia lo que se ejecuta.'
Consecuencias: 'Un solo concepto de comando reutilizable en vez de dos parecidos. Editar un script de biblioteca cambia todas las sesiones que lo usan. Los scripts bajo demanda se lanzan desde la pestaña de la sesión. El formato de config sube a 2 y las versiones anteriores de la app no leen bien las referencias.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-27T16:40:00+02:00
Última modificación: 2026-09-27T16:40:00+02:00
---

# ADR-0013 · Biblioteca de scripts unificada con los snippets

## Contexto

La tarea [[Scripts y túneles reutilizables de primera clase]] pide que los
scripts se creen una vez y se reutilicen en cualquier sesión. Al revisar el
modelo de [[ADR-0007 Modelo y persistencia de configuración]] (2026-09-27):

- Un `Snippet` solo tiene nombre, cuerpo y etiquetas. En el editor de scripts,
  elegir un snippet **copia** su cuerpo en el `SessionScript` (`snippetId`
  quedaba como recuerdo). Si luego se edita el snippet, las sesiones no se
  enteran.
- Los scripts de fase "bajo demanda" no se podían lanzar desde ningún sitio: la
  pestaña de la sesión no tenía ningún botón para ello.
- Los túneles se configuran y se guardan, pero el motor SSH nunca abre ningún
  reenvío de puertos.

Snippets y scripts de biblioteca serían dos conceptos casi iguales: un comando
con nombre que se reutiliza.

## Decisión

Decidido con el usuario el 2026-09-27:

1. **Una sola entidad, `LibraryScript`**, en `TitanConfig.scripts`. Sustituye
   a `Snippet`, y la pestaña **Scripts** de Configuración sustituye a la de
   Snippets. Guarda lo que se reutiliza: nombre, comando, etiquetas,
   comportamiento (`ScriptBehavior`, con el `expect`), variables de entorno y
   referencias a secretos.
2. **La sesión guarda cómo usa cada script.** Un `SessionScript` con
   `libraryScriptId` es una referencia: solo cuentan su fase, su posición, si
   está activado y su comportamiento al reconectar, y el resto sale de la
   biblioteca. Un `SessionScript` sin `libraryScriptId` es un script propio de
   la sesión, como hasta ahora. Una sesión puede mezclar los dos.
3. **Referencia viva.** `TitanConfig.resolve(session)` materializa las
   referencias con el contenido actual de la biblioteca, así que editar un
   script de biblioteca cambia todas las sesiones que lo usan. Una referencia a
   una entrada que ya no existe no se ejecuta.
4. **Borrar un script de biblioteca en uso** avisa de cuántas sesiones lo usan
   y deja en cada una una copia propia con su contenido. Nada deja de
   ejecutarse por borrar algo de la biblioteca.
5. **Scripts bajo demanda desde la sesión.** La pestaña de la sesión tiene un
   menú con los scripts bajo demanda de esa sesión y toda la biblioteca, y los
   envía al terminal. Se lanzan sin esperar a que terminen (no se añade la
   línea centinela a la vista del usuario); el `expect`, el retardo, las
   variables y los secretos sí se respetan.
6. **Migración de la versión 1 a la 2**, automática al cargar, con copia del
   fichero anterior. Cada snippet pasa a ser un script de biblioteca con el
   mismo id. Un script de sesión que venía de un snippet solo pasa a ser una
   referencia si su contenido coincide con el del snippet (mismo cuerpo, sin
   variables, sin secretos y con el comportamiento por defecto). Si no, se
   queda como script propio. **La migración nunca cambia lo que se ejecuta.**
7. **Los túneles quedan fuera de esta decisión.** Primero tienen que
   funcionar; la biblioteca de túneles (como plantillas) se valorará después.
   Ver [[Ejecutar los túneles de las sesiones]].

## Alternativas consideradas

- **Dos tipos separados** (Snippets como comandos sueltos y una biblioteca de
  Scripts con la configuración completa): dos conceptos casi iguales que
  conviven y confunden. Descartada.
- **Solo enlazar los snippets** (sin pestaña nueva; el script de sesión
  referencia el snippet): no permite reutilizar el comportamiento, las
  variables ni los secretos. Descartada.
- **Solo referencias** (todo script vive en la biblioteca): obliga a llenar la
  biblioteca de scripts de un solo uso. Descartada.
- **Copia al insertar**: es casi lo que ya había, y editar la biblioteca no
  llega a las sesiones. Descartada.

## Consecuencias

- Positivas: un solo concepto de comando reutilizable; los cambios de la
  biblioteca llegan a todas las sesiones; la biblioteca y los scripts bajo
  demanda se pueden usar desde una sesión abierta.
- Negativas / compromisos: el formato de config sube a la versión 2. Una
  versión anterior de la app que lea un fichero migrado ignora la biblioteca y
  las referencias (ejecutaría esos scripts vacíos). Se conserva la copia
  `config.json.v1.bak` del fichero antes de migrar.
- `Snippet`, `snippetId` e `Ids.snippet()` desaparecen del modelo; los ids
  `snippet-…` migrados se conservan, porque los ids son opacos.

Tareas relacionadas: [[Scripts y túneles reutilizables de primera clase]],
[[Ejecutar los túneles de las sesiones]]. Amplía
[[ADR-0007 Modelo y persistencia de configuración]] (sin reemplazarla: el
modelo y la persistencia siguen siendo los mismos).
