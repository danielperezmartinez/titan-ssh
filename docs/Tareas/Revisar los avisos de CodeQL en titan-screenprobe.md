---
Nombre: 'Revisar los avisos de CodeQL en titan-screenprobe'
Estado: 'Hecha'
Resumen: 'CodeQL tiene abiertos desde el 2026-10-22 dos avisos go/incorrect-integer-conversion (nº 1 y 2) en agent/cmd/titan-screenprobe/main.go, línea 130: un valor de strconv.Atoi se convierte a int16 sin comprobar el límite superior. titan-screenprobe es un cliente de desarrollo del protocolo del escritorio remoto (ADR-0029) que no se publica. Hay que revisarlos y corregirlos o descartarlos con motivo.'
Decisiones: ''
Bloqueada: []
Versión: ''
Fecha de creación: 2026-10-27T09:38:34+01:00
Última modificación: 2026-10-27T09:42:02+01:00
---

# Revisar los avisos de CodeQL en titan-screenprobe

## Objetivo

Dejar sin avisos abiertos el análisis de CodeQL del repositorio. Los dos que
quedan (`go/incorrect-integer-conversion`, severidad alta según CodeQL, nº 1 y
2) apuntan a `agent/cmd/titan-screenprobe/main.go:130`: un entero de
`strconv.Atoi` se convierte a `int16` sin comprobar el límite superior.

`titan-screenprobe` es una herramienta de desarrollo del protocolo
`titan-agent --screen` ([[ADR-0029 Escritorio remoto propio a través del agente]]):
no va en los paquetes ni en el agente publicado. Aun así conviene revisar si
el mismo patrón existe en el código que sí se publica (agente y app).

Los avisos se consultan con
`gh api "repos/danielperezmartinez/titan-ssh/code-scanning/alerts?state=open"`.

## Criterios de finalización

- Cada aviso está corregido (conversión con comprobación de rango) o
  descartado en GitHub con su motivo.
- Comprobado que el agente publicado no tiene la misma conversión sin
  comprobar.
- `go vet ./...` y `go test ./...` del agente pasan, con y sin `-tags canary`.
- El siguiente análisis de CodeQL sobre `main` no tiene avisos abiertos.

## Verificación

- `go vet ./...` y `go test ./...` del agente pasan, con y sin `-tags canary`.
- Prueba de `delta()` con un programa desechable: `5,-7` y `32767,-32768` se
  aceptan; `40000,0`, `1,99999999999`, `x,1` y `3` se rechazan.
- Revisado el agente publicado: ningún `strconv.Atoi/ParseInt/ParseUint` acaba
  en una conversión a un entero más estrecho. El `int16(x), int16(y)` de
  `internal/inject/inject_x11.go` (`MoveTo`) recibe coordenadas ya recortadas
  al tamaño de la pantalla, que en X11 cabe en `int16`.
- El análisis de CodeQL sobre `main` en el commit del arreglo (run
  `Push on main`, correcto) marcó los avisos nº 1 y 2 como `fixed`; quedan
  0 avisos abiertos.

## Resultado

Se corrigen los dos avisos en vez de descartarlos. `-rel` se lee con una función
propia, `delta()`, que usa `strconv.ParseInt(…, 10, 16)` y devuelve `int16`: un
valor fuera de rango se rechaza con un mensaje en el log en lugar de dar la
vuelta. `xy()` se queda como estaba para `-move` y `-click`, que usan `int`.
