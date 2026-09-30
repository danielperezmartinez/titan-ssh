---
Nombre: 'Detalle de las sesiones en el panel del agente'
Estado: 'Propuesta'
Ámbito: 'Layout'
Resumen: 'Amplía el panel del agente sin reemplazar Estado y control del agente en la interfaz. La fila plegada de cada sesión antepone el programa en marcha (claude · en segundo plano · último uso hace 3 h · 80 MB). Al desplegarla, antes de Abrir y Terminar, se ve el detalle, un dato por línea: si es recuperable, cuándo se creó y cuánto lleva activa, clientes o desde cuándo no tiene ninguno, el programa en marcha o si está en el prompt, la shell con su PID y tamaño, directorio (solo Linux), título de ventana, última salida, CPU y memoria, e historial guardado. Debajo va una vista previa con las últimas líneas de la terminal, en una caja con el fondo del terminal. Una sesión cuya shell terminó se ve con [x] y sin acciones. Nada de esto va en la franja de encima del terminal.'
Decisión: 'El detalle de cada sesión del agente y su vista previa se ven al desplegar su fila en el panel del agente; la fila plegada solo añade el programa en marcha.'
Consecuencias: 'La fila plegada sigue en dos líneas y el detalle solo se ve al pedirlo. La vista previa se pide al abrir el panel o al consultar, y solo vive en memoria. Los datos que el destino no da (un agente antiguo, el directorio fuera de Linux) no se muestran, sin huecos ni avisos.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-30T22:00:00+02:00
Última modificación: 2026-09-30T22:00:00+02:00
---

# Detalle de las sesiones en el panel del agente

## Contexto

El usuario pidió ver en el panel del agente qué sesiones son recuperables,
desde cuándo están activas y qué hay dentro
([[Detalle de las sesiones del agente en el panel]]), y aprobó todas las ideas
de la tarea con una condición: van en el panel del agente, nunca en la franja
de encima del terminal
([[Franja de estado del terminal solo para el estado y los scripts]]). Amplía
[[Estado y control del agente en la interfaz]] sin cambiar lo que decide.

## Decisión

Fila plegada: igual que antes, con el programa en marcha delante.

```
[-] web (ruta B)
    claude · en segundo plano · último uso hace 3 h · 80 MB
```

Fila desplegada: el detalle, luego la vista previa y luego las acciones.

```
[-] web (ruta B)
    claude · en segundo plano · último uso hace 3 h · 80 MB
      Recuperable: al abrirla vuelves a esta terminal
      creada el 27/09 16:10 · activa desde hace 3 días
      sin conectar desde el 30/09 18:02 (hace 3 h)
      en marcha: claude (PID 4121)
      shell bash (PID 4007) · 120×40
      directorio: /home/demo/proyecto
      título: demo@servidor: ~/proyecto
      última salida hace 5 min · CPU 2 % · memoria 80 MB
      historial guardado 1,2 MB
      vista previa
      ┌──────────────────────────────────────┐
      │ ~/proyecto$ claude                   │
      │ > ¿qué hago ahora?                   │
      └──────────────────────────────────────┘
      [>] Abrir
      [x] Terminar
```

- La primera línea (recuperable) va en `body`; el resto en `mute`, como los
  pies de las filas.
- Solo se muestran los datos que el destino da. Un agente antiguo deja fuera
  la shell, el programa, el directorio, el título, la CPU y la vista previa.
- La vista previa es texto plano, sin colores, en una caja con el fondo del
  terminal y scroll horizontal si una línea no cabe. Muestra hasta 8 líneas.
- Una sesión cuya shell terminó se lista con `[x]` en `mute` y
  `la shell terminó · no se puede recuperar`; desplegada, solo muestra el
  detalle, sin Abrir ni Terminar.
- Las fechas están en la hora del dispositivo y, si no son del año en curso,
  llevan el año.

## Alternativas consideradas

- Todo el detalle en la fila plegada: descartada, porque la fila crece mucho
  en el móvil.
- Una pantalla aparte por sesión: descartada de momento. Desplegar la fila ya
  da el detalle sin perder la lista.
- Vista previa con colores (pintada con `TerminalCanvas`): se deja para más
  adelante. El texto plano basta para reconocer la sesión.

## Consecuencias

- Positivas: se reconoce cada sesión sin abrirla, y la fila plegada sigue
  corta.
- Negativas / compromisos: la vista previa cuesta una orden más por sesión al
  consultar el panel, y es lo que la terminal mostraba, así que solo se guarda
  en memoria.
