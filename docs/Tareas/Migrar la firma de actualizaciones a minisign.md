---
Nombre: 'Migrar la firma de actualizaciones a minisign'
Estado: 'Pendiente'
Estado desde: 2026-11-17
Resumen: 'Cambiar la firma de titan-update.json de Ed25519 en bruto con OpenSSL a minisign, para que la clave privada esté protegida con contraseña. Se hará después de que titan-agent (otro repositorio) tenga minisign funcionando en su versión 0.6.0, para copiar una implementación ya probada. Incluye generar el par, firmar en CI con la clave cifrada, verificar el formato minisign en la app, una transición con las dos claves y la rotación.'
Decisiones: 'Pedida por el usuario el 2026-11-17, que ya tiene minisign instalado (paquete oficial de Arch). Sigue la decisión que titan-agent tomó en su ADR-0011 (repositorio titan-agent, fuera de este); aquí no se modifica nada de ese repositorio.'
Bloqueada: []
Prueba: ''
Esperando a: ''
Versión: ''
Fecha de creación: 2026-11-17T17:40:00+01:00
Última modificación: 2026-11-17T17:40:00+01:00
---

# Migrar la firma de actualizaciones a minisign

## Objetivo

Hoy el manifiesto de actualización `titan-update.json` se firma con Ed25519 en
bruto mediante OpenSSL (`tools/release/update-manifest.sh`, con
`openssl pkeyutl -rawin`). La clave privada no tiene contraseña y vive en el
secreto `TITAN_UPDATE_SIGNING_KEY`; la pública está en `shared/update-keys.txt`
(una lista, preparada para rotar) y la app verifica la firma con BouncyCastle.
Ver [[ADR-0025 Actualización automática de la app]] y
[[Actualización automática de la app]].

El usuario quiere pasar a **minisign** para que la clave privada esté protegida
con contraseña, igual que decidió titan-agent en su ADR-0011.

## Dependencia externa (no es una tarea de este repositorio)

Se hace **después** de que titan-agent tenga minisign funcionando en su versión
0.6.0, para copiar una implementación ya probada. Es trabajo del repositorio
titan-agent (ADR-0011 de ese repositorio); desde aquí solo se espera a que
exista y se consulta, sin modificarlo.

## Alcance a estudiar

- Generar el par de claves con minisign (clave privada con contraseña).
- Firmar en CI con la clave cifrada: cómo pasa la contraseña el workflow de
  release sin exponerla en los logs.
- Que la app verifique el formato de minisign: Ed25519 con prehash BLAKE2b-512
  en las firmas `ED`. BouncyCastle ya tiene BLAKE2b.
- Transición con las dos claves en la lista (`shared/update-keys.txt`) para no
  romper las actualizaciones de las versiones ya instaladas, que solo saben
  verificar el formato actual.
- Rotación: cómo se retira la clave antigua cuando ya no quede ninguna
  instalación que dependa de ella.

## Criterios de finalización

- [ ] titan-agent 0.6.0 publicada con minisign funcionando (condición previa).
- [ ] Par de claves de minisign generado y su clave pública en la lista.
- [ ] El workflow de release firma el manifiesto con la clave cifrada y verifica
  la firma en el mismo job.
- [ ] La app verifica el formato de minisign, con tests de firma válida, de otra
  clave y de manifiesto modificado.
- [ ] Una versión ya instalada con la firma antigua actualiza a la nueva sin
  romperse.
- [ ] Plan de rotación documentado y la ADR-0025 actualizada o reemplazada.

## Verificación

<Se rellena al completar.>

## Cómo probarlo

<Solo al pasar a `Por probar`.>

## Resultado

<Se rellena al completar.>
