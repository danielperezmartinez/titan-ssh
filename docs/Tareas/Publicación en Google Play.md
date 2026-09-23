---
Nombre: 'Publicación en Google Play'
Estado: 'Planificando'
Resumen: 'APLAZADA por coste (decisión del usuario, 2026-09-23): publicar en Play exige pagar una sola vez 25 $ para la cuenta de desarrollador, y hoy no está pagada. Se deja preparado lo que no cuesta nada (AAB y una clave propia que sirve como clave de subida) y reunido todo lo que habrá que hacer. Lo más lento es el requisito para cuentas personales nuevas de una prueba cerrada con al menos 12 testers durante 14 días seguidos antes de producción. Además, la ficha, la política de privacidad, el formulario de Seguridad de los datos, la clasificación de contenido y la justificación de un foreground service si se usa.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §6. Reutiliza el AAB y la clave de [[Firma y configuración de release Android]].'
Bloqueada: []
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-23T22:50:00+02:00
---

# Publicación en Google Play

> **Aplazada.** Se retoma cuando el usuario decida pagar la cuota. Mientras,
> Android se distribuye por [[Canal Android Obtainium desde GitHub Releases]] y
> [[Canal Android IzzyOnDroid]].

## Objetivo

Publicar titan-ssh en Google Play, que es el canal que llega a más usuarios de
Android.

## Checklist para cuando se retome (comprobar requisitos vigentes)

- **Cuenta**: Play Console personal, 25 $ una sola vez y verificación de
  identidad. Comprobar primero qué cuenta tiene hoy el usuario (dice que es
  "de desarrollador" sin haber pagado: probablemente no es Play Console).
- **Prueba cerrada obligatoria** para cuentas personales nuevas: **≥ 12
  testers durante 14 días seguidos** antes de poder pedir acceso a producción.
  Reunir los testers con antelación; es lo que más retrasa.
- **Pista de prueba interna** (hasta 100 testers, sin revisión): sustituiría a
  Obtainium para el propio usuario si se quiere.
- **Firma**: Play App Signing, con la clave de release actual como **clave de
  subida**. Los usuarios de Obtainium/IzzyOnDroid tienen la APK firmada con
  nuestra clave; la de Play la firmará la clave de Google, así que no se puede
  actualizar de un canal a otro sin reinstalar. Documentarlo.
- **AAB** del pipeline y subida automática desde CI (Gradle Play Publisher o una
  acción de GitHub con una cuenta de servicio de Play).
- **Ficha**: textos (reutilizar los metadatos fastlane de IzzyOnDroid), icono de
  512, gráfico de 1024×500 y capturas ([[Icono y recursos gráficos de la app]]).
- **Legal**: URL pública de política de privacidad (puede ser una página en el
  repositorio o en GitHub Pages; decir que no se recoge nada y explicar la
  consulta de versiones de [[Aviso de nueva versión en la app]], que en Play debe
  ir desactivada), formulario de Seguridad de los datos y cuestionario de
  clasificación de contenido.
- **Foreground service**: si la app mantiene sesiones SSH en segundo plano con
  un foreground service, Play exige declarar el tipo (probablemente
  `specialUse` o `dataSync`) y justificarlo, a veces con vídeo. Decidirlo antes
  de enviar a revisión.
- **targetSdk**: cumplir el mínimo exigido por Play en la fecha de envío.
- **Binarios del agente** dentro del AAB: se suben a hosts remotos y no se
  ejecutan en el dispositivo; tenerlo explicado por si la revisión pregunta.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
