---
Nombre: 'Exportar e importar la configuración sin secretos'
Estado: 'Pendiente'
Resumen: 'Permitir exportar a un fichero la configuración (hosts, sesiones, grupos, scripts, túneles y, opcionalmente, known_hosts) sin contraseñas ni claves privadas, e importarla en otro dispositivo o instalación. Así, al cambiar de móvil o de equipo, el usuario no rehace todo: solo vuelve a generar o importar sus claves y contraseñas en cada host. Sustituye a la copia de seguridad del sistema, que la app no permite.'
Decisiones: ''
Bloqueada: []
Fecha de creación: 2026-10-02T19:35:00+02:00
Última modificación: 2026-10-02T19:35:00+02:00
---

# Exportar e importar la configuración sin secretos

## Objetivo

La app no deja que Android haga copia de seguridad de sus datos ni los pase a
otro dispositivo. Los secretos tampoco se podrían restaurar en otro
dispositivo: su clave del Keystore no sale del móvil. Por eso, al cambiar de
móvil (o de equipo de escritorio) el usuario empieza de cero.

Esta tarea da una salida controlada por el usuario: exportar la configuración
a un fichero que él guarda donde quiera, e importarla después. Lo único que
tiene que rehacer es el material de autenticación de cada host.

## Alcance propuesto

- **Exportar** el `TitanConfig` (hosts, sesiones, grupos, biblioteca de
  scripts, túneles, apariencia y ajustes) a un fichero JSON con versión, el
  mismo esquema que `config.json` y sus migraciones (`ConfigMigration`).
- **Nunca** incluye secretos: el documento ya solo guarda referencias al
  `SecretStore` (ADR-0001). Al exportar se marcan como "pendiente de
  credencial" en lugar de llevarse una referencia que en el destino no existe.
  Las claves hardware (alias del Keystore) tampoco viajan.
- **`known_hosts` opcional**: son claves públicas, pero ahorran volver a
  confirmar cada host. El usuario decide si lo incluye.
- **Importar** en un dispositivo nuevo o en otro equipo: fusionar o sustituir
  la configuración actual, avisando de los conflictos por id o nombre.
- **Tras importar**, la app indica qué hosts necesitan credencial (marcador en
  la lista y aviso al conectar) y lleva al editor de host para generar o
  importar la clave o la contraseña.
- **Privacidad del fichero**: aunque no lleve secretos, describe la red del
  usuario (hosts, usuarios, scripts, túneles). Valorar cifrarlo con una
  frase de paso opcional, y dejar claro en la interfaz qué contiene.
- **Dónde**: Configuración → Ajustes. En Android, selector de documentos del
  sistema (SAF); en escritorio, diálogo de fichero. Revisar los permisos de
  Flatpak ([[Canal Linux Flatpak en Flathub]]).

## Criterios de finalización

- Exportar desde Android e importar en escritorio (y al revés) deja los hosts,
  sesiones, grupos, scripts y túneles iguales, sin ningún secreto en el
  fichero.
- Los hosts importados sin credencial se distinguen y se completan desde su
  editor; tras completarlos, conectan.
- Un fichero de una versión anterior del esquema se importa migrado.
- Tests de ida y vuelta del formato y de que el fichero no contiene secretos.

## Verificación

<Se rellena al completar: pruebas, build, comprobación real.>

## Resultado

<Se rellena al completar: qué se hizo finalmente.>
