---
Nombre: 'Canal Windows winget'
Estado: 'Pendiente'
Resumen: 'Publicar titan-ssh en winget (gratis), el gestor de paquetes de Windows, con un manifiesto en microsoft/winget-pkgs que apunta al MSI del GitHub Release. Da instalación y actualización con winget install/upgrade. El primer envío se hace a mano (wingetcreate o komac) y los siguientes se automatizan desde el release con una acción que abre el PR (necesita un fork de winget-pkgs y un token del usuario). Comprobar que se admite un MSI sin firmar mientras no haya firma de SignPath.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §4.'
Bloqueada:
  - "[[Pipeline de release en GitHub Actions]]"
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-23T22:50:00+02:00
---

# Canal Windows winget

## Objetivo

`winget install titan-ssh` y `winget upgrade` en Windows, sin tienda ni firma de
pago.

## Criterios de finalización

- Decidir el `PackageIdentifier` (forma `Editor.Paquete`, p. ej.
  `DanielPerezMartinez.TitanSSH`) con el usuario; no se puede cambiar
  fácilmente después.
- El MSI cumple lo que winget necesita: instalación silenciosa, `ProductCode` y
  `UpgradeCode` estables (el `upgradeUuid` de
  [[Configuración de release del escritorio]]) y la URL fija del Release.
- Comprobar en la política vigente de winget-pkgs si se acepta un **MSI sin
  firmar** y qué validaciones automáticas pasa (SmartScreen/Defender). Si no se
  acepta, esta tarea espera a
  [[Firma de código Windows con SignPath Foundation]].
- Primer manifiesto enviado y fusionado en `microsoft/winget-pkgs`.
- Automatización: job del release que abre el PR de la versión nueva (p. ej.
  `winget-releaser` o `komac`), con un fork de `winget-pkgs` y un token del
  usuario como secreto.
- Probado: `winget install` y `winget upgrade` en el Windows del usuario.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
