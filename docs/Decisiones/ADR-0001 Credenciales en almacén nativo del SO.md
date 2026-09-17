---
Nombre: Credenciales en el almacén nativo del SO, nunca en texto plano
Número: 1
Estado: Aceptada
Resumen: titan-ssh no guarda credenciales SSH en texto plano; una interfaz propia SecretStore (expect/actual) delega su custodia en el almacén de secretos nativo de cada plataforma (Android Keystore, Windows DPAPI/Credential Manager, Linux Secret Service).
Decisión: Definir una interfaz SecretStore en el código compartido con implementaciones por plataforma sobre el almacén nativo de secretos; nunca persistir credenciales en texto plano.
Consecuencias: commonMain no toca secretos (solo la interfaz); cada plataforma implementa su actual. La estrategia de autenticación SSH concreta se detalla en la ADR-0005.
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-17T15:32:11+02:00
Última modificación: 2026-09-17T16:34:18+02:00
---

# ADR-0001 · Credenciales en el almacén nativo del SO, nunca en texto plano

## Contexto

titan-ssh maneja conexiones SSH, por lo que la seguridad y la privacidad son un
factor crítico y una prioridad alta del proyecto. La aplicación no debe
almacenar credenciales (claves privadas, contraseñas) en texto plano ni
gestionarlas por su cuenta de forma insegura.

titan-ssh es multiplataforma (Linux, Windows y Android), así que la custodia de
secretos debe apoyarse en el mecanismo nativo de cada sistema. Tras investigar
las opciones (ver la tarea de almacenamiento seguro), se descarta depender de una
librería multiplataforma "todo en uno" inmadura y se opta por una abstracción
propia mínima con implementaciones nativas por plataforma.

## Decisión

Definir en el código compartido una interfaz mínima **`SecretStore`**
(`expect`/`actual`) y no persistir credenciales en texto plano. Implementaciones
por plataforma sobre el almacén de secretos nativo:

- **Android:** Android Keystore (AES-GCM respaldado por hardware) para cifrar y
  fichero app-private para el material cifrado; gating biométrico opcional
  (`BiometricPrompt`).
- **Windows (escritorio JVM):** DPAPI / Credential Manager mediante una librería
  Java mantenida (p. ej. credential-secure-storage-for-java o java-keyring).
- **Linux (escritorio JVM):** Secret Service (libsecret / keyring del escritorio)
  mediante la misma familia de librerías.

La autenticación SSH (clave frente a contraseña, verificación de host key y clave
no exportable en hardware) se decide en la [[ADR-0005 Autenticación SSH y verificación de host]].

## Alternativas consideradas

- Gestión propia de un almacén cifrado por la app — descartada por aumentar la
  superficie de riesgo y no aprovechar las garantías del SO.
- Librería KMP "todo en uno" (p. ej. credential-keychain-kotlin) — encaja con
  las plataformas objetivo pero está muy inmadura (0.1.0) y tira de PowerShell /
  `secret-tool` por debajo; se descarta como dependencia y se deja como
  referencia a vigilar.

## Consecuencias

- Positivas: aprovecha respaldo por hardware cuando existe, reduce el riesgo de
  fuga de credenciales, mantiene `commonMain` limpio (solo la interfaz) y evita
  atar el proyecto a una dependencia inmadura.
- Negativas / compromisos: hay que implementar y mantener el `actual` de cada
  plataforma; la elección fina de la librería de escritorio se cierra en la
  implementación (ver la tarea de almacenamiento seguro).
