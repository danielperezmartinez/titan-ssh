---
Nombre: Librería SSH (sshj en el cliente, MINA SSHD para el agente)
Número: 4
Estado: Aceptada
Resumen: El cliente SSH usa sshj (API de alto nivel, mantenida, BouncyCastle opcional desde 0.39.0, apta para Android). La reserva de Apache MINA SSHD para el agente del nivel 3 quedó ANULADA por ADR-0008 (el agente es un binario nativo Go sobre el canal exec, sin servidor SSH que implementar); sshj como cliente sigue plenamente vigente.
Decisión: Adoptar sshj como librería SSH del cliente y reservar Apache MINA SSHD para el agente propio (nivel 3) si habla SSH.
Consecuencias: sshj es pure-Java y vive en un source set JVM compartido por Android y escritorio; el keepalive/heartbeat de sshj alimenta la reconexión del nivel 1.
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-17T16:34:18+02:00
Última modificación: 2026-09-19T18:10:00+02:00
---

# ADR-0004 · Librería SSH (sshj en el cliente, MINA SSHD para el agente)

## Contexto

titan-ssh es sobre todo un cliente SSH multipestaña sobre KMP (Android +
escritorio JVM, ver [[ADR-0002 Stack KMP y alcance multiplataforma]]). Al ser
ambos objetivos JVM, una librería pure-Java sirve para las dos plataformas desde
un source set JVM compartido. Candidatos evaluados: sshj, Apache MINA SSHD y
mwiede/jsch.

## Decisión

- **Cliente: sshj.** API de alto nivel (canales `exec`/`shell`, SFTP,
  verificación de known_hosts, heartbeat integrado) que ahorra código frente a
  las alternativas. Desde la 0.39.0 (feb-2024) **BouncyCastle es opcional**, lo
  que resuelve el histórico problema de compatibilidad en Android.
- **Agente propio (nivel 3): reservar Apache MINA SSHD.** Es la única que ofrece
  **cliente y servidor**; si el agente del nivel 3 habla SSH, se construye con
  ella. No condiciona la elección del cliente por ser una pieza opcional y futura.

  > **Actualización (ADR-0008, 2026-09-19): reserva ANULADA.** El diseño del
  > agente ([[ADR-0008 Diseño del agente de resiliencia nivel 3]]) adopta la
  > **opción B** (agente ayudante sobre el canal `exec` de sshj, sin servidor SSH
  > propio) implementado como **binario nativo Go**. Sin servidor SSH que
  > implementar, MINA no aporta, y un agente JVM exigiría Java en el destino,
  > contra el objetivo de "agente ligero". Esta parte de la decisión queda
  > superada; **el resto de ADR-0004 (sshj como cliente) sigue vigente**, por lo
  > que la ADR no se marca `Reemplazada`.

## Alternativas consideradas

- **Apache MINA SSHD en el cliente** — muy potente y la más activa, pero de más
  bajo nivel y más pesada; su ventaja (servidor) no aplica al cliente. Se reserva
  para el agente.
- **mwiede/jsch** — fork mantenido que revive JSch, deps mínimas, pero de más
  bajo nivel y sin ventaja clara sobre sshj para este caso.

## Consecuencias

- Positivas: motor SSH y lógica de reconexión como código común; buena ergonomía;
  Android sin el lastre de BouncyCastle.
- Negativas / compromisos: si más adelante se quiere una única librería para
  cliente y agente, habría que reconsiderar (posible migración a MINA).

Ver [[Resiliencia de sesión ante microcortes de red]] y
[[ADR-0003 Modelo de resiliencia por niveles]].
