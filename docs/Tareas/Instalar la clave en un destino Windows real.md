---
Nombre: 'Instalar la clave en un destino Windows real'
Estado: 'Pendiente'
Estado desde: 2026-10-28
Resumen: 'Instalar la clave pública desde la app en un Win32-OpenSSH real (la VM de pruebas, usuario administrador, sin sshd_config en %ProgramData%\ssh) falla en el paso de escribir: SFTPException «No such file» que se escapa sin traducir al catálogo de errores, también con ~/.ssh ya creado. Hasta ahora el destino Windows solo se había probado con servidores falsos.'
Decisiones: 'Visto el 2026-10-28 al probar [[Clave en hardware en la app de escritorio]] en la VM de Windows; la clave TPM se probó entonces contra un sshd Linux. Sigue [[ADR-0030 Instalación de la clave pública en el host con una contraseña de un solo uso]].'
Bloqueada: []
Prueba: ''
Esperando a: ''
Versión: ''
Fecha de creación: 2026-10-28T19:10:00+01:00
Última modificación: 2026-10-28T19:10:00+01:00
---

# Instalar la clave en un destino Windows real

## Objetivo

Que «Instalar la clave en el host» funcione contra un Win32-OpenSSH real, con
un usuario normal y con un administrador, y que cualquier fallo de SFTP salga
como un error del catálogo y no como una excepción suelta.
[[Instalar la clave pública en el host desde la app]] solo lo cubrió con
servidores falsos.

## Lo observado

- Destino: la VM de Windows 11 de pruebas, usuario administrador local,
  autenticado con contraseña. En `%ProgramData%\ssh` **no hay `sshd_config`**
  (sshd arranca con los valores por defecto), pero sí existe
  `administrators_authorized_keys`.
- `KeyInstaller.install` lanza `net.schmizz.sshj.sftp.SFTPException: No such
  file` desde `SshjSftp.write` (`SFTPClient.open`). Pasa igual sin
  `%USERPROFILE%\.ssh` y después de crearlo a mano.
- `SshjSftp.write` y `mkdirs` no pasan por `serverAnswers`, así que el error no
  llega como `SshException` y `writeOverSftp`/`writeWindows` no lo traducen.

## Criterios de finalización

- Averiguar qué rama toma (administrador o usuario) cuando no hay
  `sshd_config`, qué ruta SFTP abre, y corregirlo. Ojo: sin `sshd_config`,
  sshd no tiene el `Match Group administrators`.
- Los fallos de SFTP en la escritura salen como errores del catálogo.
- Probado en la VM con un usuario administrador y con uno normal, con y sin
  `sshd_config`, con y sin `~/.ssh`.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
