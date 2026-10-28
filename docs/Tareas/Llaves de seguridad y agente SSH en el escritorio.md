---
Nombre: 'Llaves de seguridad y agente SSH en el escritorio'
Estado: 'Pendiente'
Estado desde: 2026-10-28
Resumen: 'Autenticarse con claves que no genera la app: llaves FIDO2 (sk-ssh-ed25519 / sk-ecdsa, p. ej. YubiKey), YubiKey PIV o cualquier clave que tenga el agente SSH del sistema (ssh-agent, el agente de OpenSSH de Windows, Pageant, KeePassXC, ssh-tpm-agent). Complementa la clave en el TPM. Sin prisa: el usuario la deja para más adelante.'
Decisiones: 'Aplazada al elegir el TPM en [[ADR-0033 Clave no exportable en el TPM del escritorio]] (2026-10-28). sshj no implementa los tipos sk-*; el agente SSH requiere hablar su protocolo por un socket Unix o por la tubería con nombre de Windows.'
Bloqueada: []
Prueba: ''
Esperando a: ''
Versión: ''
Fecha de creación: 2026-10-28T18:45:00+01:00
Última modificación: 2026-10-28T18:45:00+01:00
---

# Llaves de seguridad y agente SSH en el escritorio

## Objetivo

Que en el escritorio se pueda entrar con claves que viven fuera de la app: una
llave de seguridad física o una clave que ya gestiona el agente SSH del
sistema. Es el complemento a la clave en el TPM
([[Clave en hardware en la app de escritorio]]), que la propia app genera.

## Criterios de finalización

- Elegir el alcance con el usuario y anotarlo en una ADR:
  - **Agente SSH del sistema:** un nuevo método de autenticación que lista las
    identidades del agente (`SSH_AUTH_SOCK` en Linux, `\\.\pipe\openssh-ssh-agent`
    o Pageant en Windows) y le pide la firma. Así entran de una vez YubiKey
    PIV/gpg-agent, KeePassXC, ssh-tpm-agent y las llaves `sk-*` que el agente
    ya tenga.
  - **Llaves FIDO2 `sk-*` directas:** sshj no tiene esos tipos (habría que
    añadir el formato de clave y de firma). Además hay que implementar CTAP2
    por HID y, en Windows, pasar por `webauthn.dll`, porque Windows no deja a
    un usuario normal hablar directamente con la llave.
- Probado por el usuario con su llave o su agente en un Windows y un Linux
  reales.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
