---
Nombre: 'Pasar a la estable el escritorio remoto y la instalación de la clave'
Estado: 'Hecha'
Estado desde: 2026-10-29
Resumen: 'La 1.5.0 lleva a la app estable lo que hasta entonces solo ofrecía la canary: el escritorio remoto (tipo de sesión «escritorio» y --screen en el agente estable), el mouse pad en destinos Linux Wayland y «Instalar la clave en el host». La clave en hardware del escritorio (TPM) entra con ella. La web del producto deja de marcarlos como solo canary.'
Decisiones: 'El usuario decide el 2026-10-28, tras probar todo en la canary 1.4.1-canary.46, pasarlo a la estable, como preveían [[ADR-0029 Escritorio remoto propio a través del agente]] y [[ADR-0030 Instalación de la clave pública en el host con una contraseña de un solo uso]]. La instalación de la clave pasa aunque en destinos Windows siga fallando ([[Instalar la clave en un destino Windows real]]). El mecanismo de tipos experimentales y la etiqueta de Go canary se conservan para pruebas futuras. Versión 1.5.0 por SemVer (funciones nuevas).'
Bloqueada: []
Prueba: ''
Esperando a: ''
Versión: '1.5.0'
Fecha de creación: 2026-10-28T23:50:00+01:00
Última modificación: 2026-10-29T00:30:00+01:00
---

# Pasar a la estable el escritorio remoto y la instalación de la clave

## Objetivo

Que los usuarios de la estable tengan el escritorio remoto, el mouse pad en
Wayland y la instalación de la clave, ya probados por el usuario en la canary.

## Criterios de finalización

- El agente estable sirve el escritorio remoto y el mouse pad en Wayland.
- La app estable ofrece el tipo «escritorio» y «Instalar la clave en el host».
- La web del producto no los presenta como solo canary.
- Publicado en una versión estable.

## Verificación

- `go vet` (sin etiquetas, con `-tags canary`, y para Windows y macOS) y
  `go test ./...` del agente en verde.
- `:shared:desktopTest` (543 tests, 0 fallos), `:desktopApp:compileKotlin` y
  `:androidApp:assembleDebug`.
- Canary de `ec6697c` en verde y Release v1.5.0 publicada.

## Resultado

- Agente: fuera `screen_stable.go`, `screen_canary.go` y su test, y las
  comprobaciones de `screenServed` en `--screen`, `--remote-desktop`, el estado
  del escritorio remoto y la entrada Wayland del mouse pad.
- App: `SessionType.DESKTOP` deja de ser experimental, y el editor de host
  ofrece la instalación de la clave en las dos apps. Los textos del editor de
  sesión y del diagnóstico del agente dejan de decir «solo en titan-ssh
  canary».
- Web: sin los avisos de canary en `remote-desktop.html` y
  `hardware-keys.html`; la portada gana «Escritorio remoto» y dice que las
  claves pueden vivir en el TPM de Windows y Linux.
- PR #3 (`1c5bcf6`, merge `ec6697c`), tag `v1.5.0`.
