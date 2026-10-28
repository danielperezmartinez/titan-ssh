---
Nombre: 'Barra de calidad de la conexión en la franja de estado'
Estado: 'Aceptada'
Ámbito: 'Componente'
Resumen: 'En las franjas de estado del terminal, del SFTP y del mouse pad, el marcador [+] de una conexión viva se sustituye por una barra vertical de tres fragmentos apilados, como el cursor del icono de la app. Se encienden de abajo arriba según la calidad: uno en danger (sin respuesta), dos en warning (lenta) o tres en success (buena); los apagados van en surface elevada. Va sola, sin corchetes. Los demás estados conservan su marcador ASCII ([-] conectando, reconectando o en pausa; [x] caída o fallo; [!] escritorio bloqueado) y las pestañas no cambian.'
Decisión: 'Una barra de tres fragmentos sustituye a [+] solo en las franjas de estado (terminal, SFTP y mouse pad) y solo mientras la conexión está viva; el resto de estados y las pestañas siguen con los marcadores ASCII.'
Consecuencias: 'Es la única excepción gráfica al vocabulario ASCII de la interfaz, y se justifica porque repite el motivo del icono. La calidad no depende solo del color: el número de fragmentos encendidos también la dice. Las pestañas siguen mostrando [+] en verde aunque la conexión responda mal; la calidad solo se ve en la franja de la pestaña abierta.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-10-28T09:41:20+01:00
Última modificación: 2026-10-28T09:41:20+01:00
---

# Barra de calidad de la conexión en la franja de estado

Decidida con el usuario el 2026-10-28 al diseñar
[[Indicar cuando la conexión deja de responder]]. Aplica los tokens de
[[Tokens visuales dark-first base opencode]], la disciplina de color de
[[Vocabulario ASCII ampliado y disciplina de color]] y respeta
[[Franja de estado del terminal solo para el estado y los scripts]]: es el
mismo estado de la conexión, dibujado de otra forma.

## Contexto

Con la tarea de avisar cuando la conexión deja de responder, la franja necesita
distinguir varios grados dentro de "conectado". El usuario propone una barra
vertical hecha con fragmentos como el cursor de
[[Icono de la app T que sobrevive al microcorte]], que se llena como una
cobertura.

## Decisión

```
▮  Conectado · nivel 3 · agente           [>] scripts   3 en success
▮  Lenta (900 ms) · nivel 3 · agente      [>] scripts   2 en warning
▮  Sin respuesta (8 s) · nivel 3 · agente [>] scripts   1 en danger
[-] Reconectando… (intento 2)  reconectar [>] scripts   sin cambios
[x] Caída                      reconectar [>] scripts   sin cambios
```

- **Forma.** Tres fragmentos rectangulares apilados, de unos 6×4 dp con
  1,5 dp de separación, centrados en la línea de la franja. Sin corchetes, sin
  radios ni degradados, como el icono.
- **Color.** Los fragmentos se encienden de abajo arriba: tres en `success`
  (buena), dos en `warning` (lenta) y uno en `danger` (sin respuesta). Los
  apagados van en `surfaceElevated` (`#3a3636`) para que la barra conserve su
  forma.
- **Texto.** Junto a la barra, el nombre del estado y su dato: `Conectado`,
  `Lenta (… ms)` y `Sin respuesta (… s)`. Los umbrales y los nombres exactos se
  fijan en la tarea. En el SFTP y el mouse pad, con la conexión buena se ve su
  texto de siempre (`SFTP`, `Conectado al escritorio`) y, si va lenta o no
  responde, el de la calidad en su lugar.
- **Solo con la conexión viva.** Conectando, reconectando, en pausa, caída y
  fallo conservan `[-]` y `[x]`: sin conexión no hay calidad que medir, y un
  fragmento rojo no debe confundirse con una caída.
- **En todas las franjas de estado, no en las pestañas.** La barra va en la
  franja del terminal, del SFTP (`SFTP`) y del mouse pad (`Conectado al
  escritorio`), para que el estado de la conexión se vea igual en todas las
  vistas de una pestaña. El `[!]` de escritorio bloqueado no cambia. Las
  pestañas siguen con `[+]`, `[-]` y `[x]` como hasta ahora (decisión del
  usuario).
- **Umbrales.** Viven en [[Indicar cuando la conexión deja de responder]],
  porque se ajustarán al probarlos.

## Alternativas consideradas

- **Barra entre corchetes `[▮]`, del ancho de `[+]`**: mantiene alineada la
  columna del marcador al cambiar de estado. El usuario prefiere la barra sola,
  más fiel al icono.
- **Barra también en las pestañas**: permitiría ver qué sesión va mal sin
  abrirla. El usuario prefiere dejar las pestañas como están.
- **Barra en todos los estados** (vacía en caída, animada al reconectar):
  descartada porque un fragmento rojo significaría a la vez "sin respuesta" y
  "caída".

## Consecuencias

- Positivas: el estado de la conexión repite el motivo del icono; la calidad se
  lee por número de fragmentos además de por color; los estados sin conexión no
  cambian.
- Negativas / compromisos: es un elemento gráfico dentro de una interfaz ASCII;
  el texto de la franja se desplaza un poco cuando la barra cambia a `[-]` o
  `[x]`, porque la barra es más estrecha; las pestañas no muestran la calidad.
