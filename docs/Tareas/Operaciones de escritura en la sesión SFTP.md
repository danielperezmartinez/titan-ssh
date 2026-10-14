---
Nombre: 'Operaciones de escritura en la sesión SFTP'
Estado: 'Pendiente'
Resumen: 'Petición del usuario (2026-10-14), para más adelante. La sesión SFTP nace de solo lectura (navegar y descargar). Esta tarea añade el resto: subir archivos desde el dispositivo, crear carpetas, renombrar, borrar y, si se decide, cambiar permisos, con confirmación en lo destructivo y subidas reanudables como las descargas.'
Decisiones: 'Sale de [[Sesión SFTP para navegar y descargar archivos]] y de [[ADR-0021 Sesión SFTP de solo lectura con descargas reanudables]], que limitan la primera versión a solo lectura.'
Bloqueada: []
Fecha de creación: 2026-10-14T10:50:00+02:00
Última modificación: 2026-10-14T14:40:00+02:00
---

# Operaciones de escritura en la sesión SFTP

## Objetivo

Completar el explorador SFTP con las operaciones que modifican el destino.

## Alcance previsto

- Subir uno o varios archivos (en Android con el selector de documentos).
- Crear carpeta, renombrar, borrar (con confirmación; borrar carpetas no
  vacías, a decidir).
- Cambiar permisos (a decidir).
- Subidas reanudables tras un corte, igual que las descargas.
- Descargar una carpeta entera (recursiva).

## Criterios de finalización

- Cada operación funciona contra destinos Unix y Windows.
- Probado en el emulador y en escritorio.

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
