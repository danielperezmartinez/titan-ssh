---
Nombre: 'Aviso de nueva versión en la app'
Estado: 'Pendiente'
Resumen: 'Para las instalaciones sin actualizador propio (MSI, .deb, .rpm y tar.gz sueltos), la app consulta la API pública de GitHub Releases y avisa, sin descargar ni instalar nada, cuando hay una versión estable más nueva, con un enlace al Release. Se desactiva en los canales que ya actualizan solos (Flatpak, AUR, winget, Obtainium, IzzyOnDroid y Play). Por el pilar de privacidad, la petición no lleva datos del usuario ni de sus hosts y se puede desactivar en los ajustes.'
Decisiones: 'Sigue [[ADR-0011 Distribución y canales de publicación]] §4. Pendiente con el usuario: si viene activado o desactivado por defecto.'
Bloqueada:
  - "[[Pipeline de release en GitHub Actions]]"
Fecha de creación: 2026-09-23T22:50:00+02:00
Última modificación: 2026-09-23T22:50:00+02:00
---

# Aviso de nueva versión en la app

## Objetivo

Que quien instaló un MSI o un `.deb` suelto se entere de que hay versión nueva,
sin montar un actualizador propio.

## Criterios de finalización

- Consulta a `GET https://api.github.com/repos/danielperezmartinez/titan-ssh/releases/latest`
  (solo estables) como mucho una vez al día, sin autenticación y sin
  identificadores del usuario. Si falla, no se hace nada: sin errores visibles
  y sin reintentos agresivos.
- Comparación SemVer con la versión de la app
  ([[Versionado único desde tag de git]]).
- Aviso discreto (banner o entrada en Acerca de) con enlace al Release; nunca
  descarga ni ejecuta nada.
- **Detección de canal**: desactivado si la app corre en Flatpak
  (`FLATPAK_ID`), o si se instaló por AUR, winget o una tienda Android. Por
  ejemplo, una propiedad del canal fijada en el build o en el lanzador de cada
  paquete.
- Ajuste para desactivarlo; decidir con el usuario el valor por defecto
  (privacidad frente a utilidad). Documentar la petición saliente en el README.
- Código común en `commonMain` detrás de una interfaz; red y detección de canal
  en los source sets de plataforma (regla de dependencias del [[README]]).

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
