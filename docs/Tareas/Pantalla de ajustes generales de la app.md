---
Nombre: 'Pantalla de ajustes generales de la app'
Estado: 'Pendiente'
Resumen: 'Petición del usuario (2026-10-14), para más adelante. Una pantalla de configuración general de la app donde reunir las opciones que no son de un host ni de una sesión. La primera nueva es la carpeta de destino de las descargas SFTP, que de momento van siempre a Descargas. Hoy existe la pestaña Ajustes de Configuración (AppSettings), que solo se muestra donde hay alguna opción (permitir capturas en Android) y además guarda la barra de teclas.'
Decisiones: 'Sale de [[Sesión SFTP para navegar y descargar archivos]] y de [[ADR-0021 Sesión SFTP de solo lectura con descargas reanudables]], que fijan Descargas como destino por ahora. Partir de la pestaña Ajustes y de AppSettings existentes, no crear un segundo sitio para lo mismo.'
Bloqueada: []
Fecha de creación: 2026-10-14T10:50:00+02:00
Última modificación: 2026-10-14T10:50:00+02:00
---

# Pantalla de ajustes generales de la app

## Objetivo

Tener un único sitio, visible en todas las plataformas, para los ajustes
generales de la app, y llevar allí las opciones que hoy están sueltas o fijas.

## Contenido previsto

- **Carpeta de destino de las descargas SFTP** (hoy fija en Descargas). En
  Android, con el selector de carpetas del sistema (acceso persistente a un
  árbol de documentos); en escritorio, con el diálogo de carpetas.
- Lo que ya hay en Configuración → Ajustes: permitir capturas de pantalla.
- Revisar si la barra de teclas configurable debe enlazarse desde aquí.
- Otras opciones globales que vayan apareciendo.

## Por decidir al empezarla

- Si es la pestaña Ajustes de Configuración ampliada (y visible siempre) o
  una pantalla propia abierta desde la cabecera.
- Si preguntar el destino en cada descarga es una opción más.

## Criterios de finalización

- La pantalla existe en Android y escritorio y permite elegir la carpeta de
  descargas, que la sesión SFTP respeta.
- Probado en el emulador y en escritorio.

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
