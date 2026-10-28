---
Nombre: 'Conexiones en segundo plano según lo que pueda perder cada pestaña'
Estado: 'Por probar'
Estado desde: 2026-10-14
Resumen: 'Implementar ADR-0023 en Android: servicio en primer plano mientras alguna pestaña pueda perder trabajo (nivel 1, conectando, túneles activos, descarga SFTP en curso), con todas las pestañas conectadas; si ninguna puede, pausar las conexiones tras 60 s en segundo plano y reconectar al volver; y no reintentar nunca con la red bloqueada por Android.'
Decisiones: 'Sale de [[Medir el consumo de batería en segundo plano]]. Diseño en [[ADR-0023 Conexiones en segundo plano según lo que pueda perder cada pestaña]] (aceptada el 2026-10-14). Mejora posterior: [[Alinear los keepalives de las pestañas]]. En la implementación, "todavía conectando" de la ADR se concreta como conectada sin nivel conocido y sin esperar al agente: una pestaña que aún no ha conectado nunca no tiene nada que perder y no arranca el servicio (evita que la notificación aparezca y desaparezca al abrir una sesión de nivel 3).'
Bloqueada: []
Prueba: 'Una noche en el Pixel con una pestaña de nivel 1 abierta y la pantalla bloqueada: la sesión sigue viva por la mañana y el consumo de la app es razonable.'
Esperando a: ''
Fecha de creación: 2026-10-14T20:00:00+02:00
Última modificación: 2026-10-28T12:00:00+01:00
---

# Conexiones en segundo plano según lo que pueda perder cada pestaña

## Objetivo

Que las sesiones de nivel 1 sobrevivan al bloqueo de pantalla y que la app no
gaste batería en segundo plano cuando no hay nada que perder, según
[[ADR-0023 Conexiones en segundo plano según lo que pueda perder cada pestaña]].

## Alcance

1. Regla común por pestaña ("necesita seguir conectada") a partir del nivel
   efectivo, los túneles activos y las descargas SFTP en curso.
2. Android: servicio en primer plano (`specialUse` salvo que la prueba diga
   otra cosa) arrancado con la app delante cuando alguna pestaña lo necesita,
   con notificación y petición de permiso de notificaciones.
3. Android: pausa tras 60 s en segundo plano si ninguna pestaña lo necesita, y
   reconexión inmediata al volver. Estado "en pausa" en la franja.
4. No reintentar con la red bloqueada (`onBlockedStatusChanged`), sin consumir
   el plazo de 15 min; el aviso de vuelta de la red no relanza nada mientras
   siga bloqueada.

## Criterios de finalización

- Emulador: con una pestaña de nivel 1 y la pantalla apagada 15 min, la sesión
  sigue conectada y el shell intacto; el sshd no ve reconexiones.
- Emulador: con solo pestañas de nivel 3, tras 60 s en segundo plano no queda
  ninguna conexión ni ningún intento (logcat sin `tagSocket` de la app) y al
  volver la pantalla y la línea a medio escribir están intactas.
- Ningún intento de conexión mientras la red está bloqueada.
- Pixel: una noche con el servicio activo sin perder la sesión de nivel 1, y
  `batterystats` comparado con la referencia de
  [[Medir el consumo de batería en segundo plano]].
- Tests de la regla común y del ciclo de pausa con reloj virtual.

## Implementación

- `SessionTab`: regla `needsConnection` y flujo `keepAlive`; `pause()` cierra
  limpiamente agente (BYE), shell y conexión y deja la pestaña en la fase nueva
  `TabPhase.PAUSED` sin reintentos; `resume()` reconecta como tras un corte.
  Con la red bloqueada (`networkBlocked`) el bucle espera sin intentar y ese
  tiempo no cuenta en el plazo de 15 min.
- `BackgroundConnections` (código común): pide el servicio mientras alguna
  pestaña lo necesite y pausa todas tras 60 s en segundo plano si ninguna lo
  necesita; al volver las reanuda.
- Android: `networkBlocked()` con `onBlockedStatusChanged`;
  `AppVisibilityEffect` (ciclo de vida de la actividad) y `KeepAliveService`
  (arranca con la app delante, pide una vez el permiso de notificaciones,
  vuelve a publicar la notificación en `ON_RESUME`); servicio
  `KeepAliveService` de la app con tipo `specialUse`.
- UI: "En pausa" en la franja de estado de terminal, SFTP y mouse pad.

## Verificación

**Tests** (2026-10-14): `SessionTabBackgroundTest`, 7 casos con reloj virtual
(la regla, pausa y reanudación con el historial, pausa durante el backoff,
pausar una pestaña terminada, red bloqueada una hora sin intentos ni consumo
del plazo, servicio con una pestaña de nivel 1, pausa al minuto y reanudación).
`SessionTabReconnectTest` sigue en verde (14). Suite de escritorio: 409 tests,
1 fallo ajeno (`DesktopSecretStoreTest`, error 1312 por ejecutarse en la
sesión 0 de Windows). APK de debug compilada.

**Emulador** `Pixel_9_Pro_XL` (Android 15), build del worktree contra un sshd
de medición aparte (misma imagen que `tools/test-sshd/`):

- Nivel 1 ("proyecto demo", con túneles): al abrirla arranca el servicio
  (`isForeground=true`, tipo `specialUse`) y pide el permiso de
  notificaciones; la notificación dice "1 sesión conectada". Con la pantalla
  apagada 5 min: `procState=FGS`, red permitida (`effective=NONE`), la
  conexión sigue establecida todo el rato y el sshd no ve ninguna reconexión.
- Al cerrar esa pestaña el servicio se para.
- Solo nivel 3 ("demo (agente)", sin servicio): con `echo parcial` escrito sin
  Enter, pantalla apagada 4 min: red bloqueada, **ningún intento de
  conexión** (antes, 4–8 por minuto) y CPU plana desde la pausa. Al
  desbloquear reconecta en pocos segundos con una sola conexión nueva, la
  línea a medias sigue ahí y al pulsar Enter se ejecuta (`parcial`): estaba en
  el shell remoto.

**Hallazgo menor, no tocado**: un túnel que falla por puerto ocupado se
reintenta mientras la conexión vive (`SessionTunnels.retryFailed`: cada 5 s y
luego cada 30 s). Es un `bind` local sin tráfico de red, pero con el servicio
activo despierta la app cada 30 s.

**Pendiente**: la prueba en el Pixel (una noche con el servicio activo sin
perder la sesión de nivel 1, si los keepalives salen a tiempo con la CPU
dormida, y `batterystats` frente a la referencia).

## Cómo probarlo

**Dónde**: la versión estable actual en el Pixel (lleva el cambio desde la
`0.1.0-beta.17`). Destino: cualquiera por SSH, sin agente (nivel 1).

**Preparación**: el móvil cargado y desenchufado. Opcional, con el móvil
conectado al PC: `adb shell dumpsys batterystats --reset`.

**Pasos**:

1. Abrir una pestaña de nivel 1 y dejar algo corriendo, p. ej.
   `while true; do date; sleep 300; done`. La notificación dice «1 sesión
   conectada».
2. Bloquear el móvil y dejarlo toda la noche sin cargar.
3. Por la mañana, abrir la app: la pestaña sigue en la misma shell (la hora
   sigue saliendo y no hay reconexión).
4. Ajustes → Batería → uso por app: anotar el porcentaje de titan-ssh.
   Opcional, con el móvil conectado al PC:
   `adb shell dumpsys batterystats io.github.danielperezmartinez.titanssh > batterystats.txt`.

**Qué debe pasar**: la sesión de nivel 1 sobrevive a la noche y la app no
aparece entre los consumos grandes.

**Si falla**: a qué hora se cortó si se ve, el porcentaje de batería, una
captura del uso por app y `batterystats.txt`.

La misma noche sirve para [[Medir el consumo de batería en segundo plano]].

## Resultado

<Se rellena al completar.>
