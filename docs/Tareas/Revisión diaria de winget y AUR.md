---
Nombre: 'Revisión diaria de winget y AUR'
Estado: 'En curso'
Resumen: 'Comprobación que se repite cada día hasta cerrar los dos canales del paso 7, que dependen de terceros: que Microsoft valide y fusione el primer PR de winget (microsoft/winget-pkgs#441824) y que AUR reabra el registro de cuentas. En cada sesión en la que el usuario la nombre, el agente comprueba los dos, le dice qué le toca hacer o, si nada ha cambiado, que se deja para mañana, y anota la revisión en el registro. Recoge los pasos que faltan hasta activar las variables WINGET_ENABLED y AUR_ENABLED y probar winget y yay; el detalle técnico vive en las tareas de cada canal.'
Decisiones: 'Pedida por el usuario el 2026-09-26 para revisar el estado cada día. No sustituye a [[Canal Windows winget]] ni a [[Canal Arch Linux AUR]]: es la rutina de seguimiento de las dos. La página de registro de AUR tiene una protección contra bots (Anubis) que el agente no debe saltarse, así que el registro lo mira el usuario en su navegador y el agente solo lee las noticias de Arch.'
Bloqueada: []
Fecha de creación: 2026-09-26T20:30:00+02:00
Última modificación: 2026-09-28T10:00:00+02:00
---

# Revisión diaria de winget y AUR

## Cómo usar esta nota

Cuando el usuario nombre esta tarea o pregunte qué tiene pendiente:

1. **Comprobar winget** (el agente, sin autenticarse):
   ```
   curl -s https://api.github.com/repos/microsoft/winget-pkgs/pulls/441824 | jq -r '.state, .merged'
   curl -s https://api.github.com/repos/microsoft/winget-pkgs/issues/441824 | jq -r '[.labels[].name] | join(", ")'
   curl -s https://api.github.com/repos/microsoft/winget-pkgs/issues/441824/comments | jq -r '.[] | "\(.created_at) \(.user.login): \(.body[0:300])"'
   ```
   Si el usuario lo prefiere, también se pueden revisar sus correos de GitHub
   sobre la PR con el conector de Gmail, solo para leerlos.
2. **Comprobar AUR**:
   - El agente lee las noticias de Arch
     (`curl -s https://archlinux.org/feeds/news/`) por si anuncian la
     reapertura del registro. También se anuncia en la lista aur-general.
   - 👤 El usuario abre <https://aur.archlinux.org/register> en su navegador.
     Si sigue saliendo *New account registration is temporarily closed*,
     sigue cerrado. El agente no consulta esa página: tiene una protección
     contra bots y el propio aviso pide no automatizar reintentos.
3. **Decidir** con la tabla de **Qué hacer según lo que se encuentre** y
   decírselo al usuario. Si nada ha cambiado: **"Sin cambios en winget ni en
   AUR: lo dejamos para mañana."**
4. Añadir una línea al **Registro** con la fecha y lo encontrado, marcar
   aquí los pasos hechos y actualizar `Última modificación`. Si un paso cambia
   el estado de una de las tareas de canal, actualizarla también.

## Qué hacer según lo que se encuentre

| Situación | Qué significa | Qué se hace |
|---|---|---|
| PR `open`, solo `New-Package` (o etiquetas de validación en verde) | En validación o esperando al moderador | Nada. Lo dejamos para mañana. |
| PR con etiqueta roja (`Validation-*-Error`, `Binary-Validation-Error`, `URL-Validation-Error`…) | La validación ha fallado | El agente lee el informe que enlaza el bot y propone el arreglo. Los cambios del manifiesto se suben a la rama `danielperezmartinez-patch-1` del fork. |
| Comentario de un moderador pidiendo cambios (`Needs-Author-Feedback`) | Hay que responder | El agente propone la respuesta o el cambio y el usuario lo revisa antes de enviarlo. |
| PR `closed` y `merged: true` | Paquete publicado en winget | Seguir los pasos de **winget** desde el 1. |
| PR `closed` sin fusionar | Rechazada | Leer el motivo y decidir con el usuario. |
| El registro de AUR sigue cerrado | — | Nada. Lo dejamos para mañana. |
| El registro de AUR está abierto | Se puede crear la cuenta | Seguir los pasos de **AUR** desde el 1. |

## Pasos que faltan

### winget ([[Canal Windows winget]])

Ya hecho: fork, primer PR
([#441824](https://github.com/microsoft/winget-pkgs/pull/441824)), CLA
firmado y token en el secreto `WINGET_TOKEN`.

1. [ ] Esperar a que Microsoft valide y fusione la PR. La validación pasó el 2026-09-26; falta la aprobación de un moderador. Hay que revisarla
   cada día.
2. [ ] 👤 Con la PR fusionada, comprobar `winget search titan-ssh` (el índice
   tarda unas horas en actualizarse) y probar
   `winget install DanielPerezMartinez.TitanSSH.Beta` en Windows.
3. [ ] 👤 Crear la variable del repositorio `WINGET_ENABLED` = `true` en
   <https://github.com/danielperezmartinez/titan-ssh/settings/variables/actions/new>
   (pestaña *Variables*, no *Secrets*). **No antes de la fusión**, porque el
   job fallaría.
4. [ ] Con la siguiente beta (solo cuando el usuario pida publicarla),
   comprobar que el job `winget` acaba bien y abre un PR `Update:` en
   winget-pkgs. Ese PR se sigue igual que el primero.
5. [ ] 👤 Con esa versión fusionada, `winget upgrade DanielPerezMartinez.TitanSSH.Beta`.
6. [ ] Añadir la sección de winget al `README.md` de la raíz y pasar
   [[Canal Windows winget]] a `Hecha`.

### AUR ([[Canal Arch Linux AUR]])

Ya hecho: plantilla, job, clave SSH y secreto `AUR_SSH_PRIVATE_KEY`.

1. [ ] 👤 Esperar a que AUR reabra el registro. Hay que revisarlo cada día.
2. [ ] 👤 Crear la cuenta de AUR y pegar la clave pública (está en el gestor
   de contraseñas del usuario) en **My Account → SSH Public Key**.
3. [ ] Primer envío de `titan-ssh-beta-bin`: o lo sube el usuario desde su
   Arch con los comandos que le dé el agente, o se deja que lo cree el job
   con la próxima beta. Ver la sección **Pendiente** de
   [[Canal Arch Linux AUR]].
4. [ ] 👤 Crear la variable del repositorio `AUR_ENABLED` = `true` en
   <https://github.com/danielperezmartinez/titan-ssh/settings/variables/actions/new>.
5. [ ] Con la siguiente beta, comprobar que el job `aur` acaba bien y que
   aparece <https://aur.archlinux.org/packages/titan-ssh-beta-bin>.
6. [ ] 👤 En el Arch del usuario, `yay -S titan-ssh-beta-bin`, arrancar la app
   y, con la beta siguiente, `yay -Syu` y `yay -R`.
7. [ ] Añadir la sección de AUR al `README.md` de la raíz y pasar
   [[Canal Arch Linux AUR]] a `Hecha`.

### Cierre

- [ ] Con winget y AUR hechos, pasar esta tarea a `Hecha` y marcar el paso 7
  y la entrada de AUR en [[Seguimiento de tareas pendientes]].

## Registro

- 2026-09-26 — Creada. **winget**: PR abierta, CLA firmado, etiqueta
  `New-Package` y validación en marcha, sin comentarios de moderadores.
  **AUR**: registro cerrado (lo comprobó el usuario) y sin noticias de
  reapertura en el feed de Arch. Lo dejamos para mañana.
- 2026-09-27 — **winget**: la validación automática ha pasado (etiquetas
  `Azure-Pipeline-Passed` y `Validation-Completed`). El bot avisó el
  2026-09-26 de que un moderador voluntario tiene que aprobar la PR, y aún no
  está fusionada. **AUR**: registro cerrado (página abierta en el navegador del
  usuario) y sin noticias en el feed de Arch. Lo dejamos para mañana.
- 2026-09-28 — **winget**: la PR sigue abierta y sin fusionar, con las mismas
  etiquetas (`Azure-Pipeline-Passed`, `Validation-Completed`, `New-Package`)
  y sin actividad desde el 2026-09-26. **AUR**: sin noticias de reapertura en
  el feed de Arch (la entrada más reciente relacionada es el incidente de
  paquetes maliciosos). La página de registro está tras un reto anti-bots que
  no se puede comprobar por `curl`: 👤 la revisa el usuario en el navegador.
  Lo dejamos para mañana.
