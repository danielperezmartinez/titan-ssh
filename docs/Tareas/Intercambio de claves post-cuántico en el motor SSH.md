---
Nombre: 'Intercambio de claves post-cuántico en el motor SSH'
Estado: 'Pendiente'
Resumen: 'El motor SSH (sshj) no ofrece ningún intercambio de claves post-cuántico, así que el tráfico que se grabe hoy podría descifrarse en el futuro con un ordenador cuántico ("guardar ahora, descifrar después"). OpenSSH ya usa por defecto mlkem768x25519-sha256 (desde la 10.0) y avisa cuando no se negocia uno así. sshj no lo implementa: hay que escribirlo sobre el ML-KEM de Bouncy Castle 1.86 y ponerlo el primero de la lista de intercambio de claves. No es urgente: es protección a futuro, no un fallo actual.'
Decisiones: ''
Bloqueada: []
Fecha de creación: 2026-10-02T14:30:00+02:00
Última modificación: 2026-10-02T14:30:00+02:00
---

# Intercambio de claves post-cuántico en el motor SSH

## Objetivo

Al conectar, cliente y servidor acuerdan una clave de sesión con un
intercambio de claves (hoy, `curve25519-sha256`). Es seguro frente a los
ordenadores actuales, pero un ordenador cuántico suficientemente grande
podría romperlo. El riesgo práctico es **"guardar ahora, descifrar
después"**: alguien graba hoy el tráfico cifrado y lo descifra cuando exista
esa máquina. Lo que se teclea en una sesión (incluidas contraseñas de `sudo`)
podría seguir valiendo entonces.

Los intercambios **híbridos** combinan una curva clásica con un algoritmo
post-cuántico, así que no son más débiles que los actuales aunque el
post-cuántico fallara:

| Algoritmo | Disponible en OpenSSH | Por defecto en OpenSSH |
| --- | --- | --- |
| `mlkem768x25519-sha256` (ML-KEM, FIPS 203) | 9.9 | 10.0 |
| `sntrup761x25519-sha512` (y su alias `@openssh.com`) | 8.5 | 9.0 a 9.9 |

sshj 0.41.1 no implementa ninguno. Bouncy Castle 1.86, que ya va en la app,
trae ML-KEM y sNTRU Prime.

Fuera de alcance: firmas post-cuánticas para las claves de host y de usuario.
OpenSSH no las tiene todavía y el riesgo de "guardar ahora, descifrar después"
no se aplica a la autenticación.

## Criterios de finalización

- [ ] Implementar `mlkem768x25519-sha256` como `KeyExchange` de sshj en
  `jvmSharedMain`, con ML-KEM-768 y X25519 de Bouncy Castle, según
  `draft-ietf-sshm-mlkem-hybrid-kex`:
  - el cliente envía su clave pública ML-KEM-768 seguida de su clave X25519;
  - el servidor responde con el texto cifrado ML-KEM seguido de su clave
    X25519;
  - el secreto compartido es `SHA-256(secreto ML-KEM || secreto X25519)` y en
    el hash de intercambio va codificado como `string`, no como `mpint`.
- [ ] Valorar `sntrup761x25519-sha512` para servidores OpenSSH 8.5 a 9.8, que
  no tienen ML-KEM. Si no compensa, anotar aquí el motivo.
- [ ] Ponerlos los primeros en la lista de intercambio de claves del motor
  (`SshjConfig.kt`) y actualizar su test.
- [ ] Comprobar que funciona en Android (Bouncy Castle completo registrado sin
  forzar, ver `SshAndroidCrypto`) y con R8 (las reglas de
  `androidApp/proguard-rules.pro` ya conservan Bouncy Castle entero).
- [ ] Tests con vectores conocidos del borrador, o como mínimo contra un
  servidor OpenSSH real.

## Verificación

<Se rellena al completar. Mínimo:
- test de la lista de algoritmos y tests de escritorio;
- conexión contra un OpenSSH ≥ 9.9 que negocie `mlkem768x25519-sha256` (el
  `titan-test-sshd` actual lleva OpenSSH 9.7: hará falta una imagen más
  nueva);
- `ssh-audit -c` en escritorio y Android sin el aviso de post-cuántico;
- APK de release (R8) en el emulador.>

## Resultado

<Se rellena al completar.>
