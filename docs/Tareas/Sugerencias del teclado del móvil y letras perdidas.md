---
Nombre: 'Sugerencias del teclado del móvil y letras perdidas'
Estado: 'Hecha'
Resumen: 'En Android el teclado del sistema no muestra sugerencias en el terminal ni en el mouse pad, y al escribir muy deprisa a veces se pierden letras. Se añaden en Ajustes dos interruptores (sugerencias del teclado, y un botón [abc] junto a [kbd] para encenderlas o apagarlas al momento), el teclado nunca aprende lo tecleado, y se busca y corrige la pérdida de letras.'
Decisiones: 'Las sugerencias se activan en Ajustes → Teclado del móvil, un ajuste global solo de Android que vale para el terminal y el mouse pad. El botón [abc] no sale por defecto: lo muestra un segundo interruptor con su explicación. Con sugerencias o sin ellas, el campo pide IME_FLAG_NO_PERSONALIZED_LEARNING (cubre ese punto de [[Endurecer la entrada del mouse pad y del teclado del móvil]]).'
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-27T12:00:00+01:00
Última modificación: 2026-10-27T14:00:00+01:00
---

# Sugerencias del teclado del móvil y letras perdidas

## Objetivo

El teclado del móvil escribe en el terminal y en el mouse pad a través de un
campo oculto (`SoftKeyboardCapture`, en `TerminalView.kt`). Tiene dos
problemas que ha notado el usuario:

1. **No hay sugerencias** como en otras apps (WhatsApp, etc.) en el móvil del
   usuario. El campo pedía `autoCorrectEnabled = false` y no decía nada más
   de las sugerencias: cada teclado hacía lo suyo (Gboard en el emulador sí
   sugería), y nada le impedía aprender lo tecleado.
2. **Letras perdidas al escribir muy deprisa.** El envío sale en orden por un
   mutex; las letras se perdían al recibirlas. El campo se vaciaba cada 64
   caracteres y en cada Enter, y vaciarlo reinicia la conexión con el
   teclado: la pulsación que ya iba hacia la conexión vieja se descartaba
   (en el emulador, 78 pulsaciones en 1,9 s perdían siempre la 65.ª).

## Criterios de finalización

- [x] **Ajustes → Teclado del móvil** (solo Android): interruptor
  "Sugerencias del teclado" con una línea que lo explique. Apagado de serie.
- [x] Segundo interruptor que muestra el botón `[abc]` junto a `[kbd]` en la
  barra de teclas del terminal y del mouse pad, con su línea explicativa.
  Apagado de serie. El botón enciende o apaga las sugerencias al momento
  (p. ej. antes de escribir una contraseña).
- [x] Con sugerencias, elegir una sugerencia o que el teclado corrija una
  palabra llega bien al destino (borrados + palabra nueva).
- [x] El campo pide `IME_FLAG_NO_PERSONALIZED_LEARNING` siempre.
- [x] Reproducida la pérdida de letras al escribir deprisa, encontrada su
  causa y corregida, con test.
- [x] Probado en el emulador en el terminal y en el mouse pad, con capturas.

## Verificación

Hecho el 2026-10-27 en el emulador (`Pixel_9_Pro_XL`, Gboard, build de
debug del worktree) contra `titan-test-sshd` por `adb reverse` a
`127.0.0.1:2222`, y con `:shared:desktopTest` (519 tests en verde, entre
ellos `SoftKeyboardEditsTest`). El mismo día el usuario lo probó en su móvil
y dio el visto bueno.

- **Lo que pide el campo** (`dumpsys input_method`): sin sugerencias,
  `inputType=0xa0091` (texto, contraseña visible, sin sugerencias,
  multilínea); con sugerencias, `0x28001` (texto, autocorrección,
  multilínea). En los dos casos `imeOptions` lleva
  `IME_FLAG_NO_PERSONALIZED_LEARNING` y Gboard muestra el icono de
  incógnito. `[abc]` cambia de uno a otro al momento, sin cerrar el teclado.
- **Letras perdidas**: ráfagas de 78 pulsaciones en Gboard (unas 40 por
  segundo) volcadas a `cat >k` en el destino. Antes se perdía la 65.ª; ahora
  llegan exactas tres veces seguidas con y sin sugerencias. Con Enter entre
  palabras a unas 10 pulsaciones por segundo llega todo y en orden. Solo a 40
  por segundo Gboard entrega a veces el Enter una letra tarde (va por otra
  cola que el texto), y con sugerencias parte una "palabra" de más de 64
  letras con un espacio: las dos son cosa del teclado y no se dan al
  escribir a mano.
- **Sugerencias**: con ellas, elegir "echo" tras teclear `ech` llega como
  `echo `, y Gboard corrige al pulsar espacio (`lst` → `last`); la
  explicación del ajuste lo avisa.
- **Mouse pad**: sesión de tipo mouse pad contra el mismo contenedor (sin
  escritorio, así que solo se comprueba la barra y el teclado): `[abc]` sale
  junto a `[kbd]`, enciende y apaga las sugerencias y el campo pide lo mismo
  que en el terminal. El envío es el mismo `SoftKeyboardCapture`.

## Resultado

- `AppSettings` gana `keyboardSuggestions` y `keyboardSuggestionsButton`
  (apagados de serie, con valor por defecto, así que la configuración no
  cambia de versión), y Ajustes una sección **Teclado del móvil** solo en
  Android con los dos interruptores y su explicación.
- `KeyBarRow` pone `[abc]` fijo junto a `[kbd]` cuando el segundo ajuste está
  encendido; resaltado mientras hay sugerencias. Cambia el ajuste global.
- `SoftKeyboardIme` (expect/actual): en Android envuelve el campo con un
  `PlatformTextInputInterceptor` que edita el `EditorInfo` (siempre sin
  aprendizaje; sin sugerencias, contraseña visible y `NO_SUGGESTIONS`). En
  escritorio no hace nada.
- `SoftKeyboardEdits` / `SoftKeyboardCapture`: tras un Enter o una línea
  larga el campo se vacía cuando el teclado lleva 800 ms quieto, no a mitad
  de una ráfaga. Si se borra el ancla, se repone al momento, como antes.
