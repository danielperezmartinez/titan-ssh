---
Nombre: 'Auditoría 2026-10-01 Completa'
Tipo: 'Completa'
Estado: 'En curso'
Fecha: 2026-10-01
Versión auditada: 'v0.1.0-beta.9 + 2 commits (4691034)'
Resumen: 'Primera auditoría de seguridad del proyecto, con la que nace este sistema. Revisión estática del código de cliente, almacén de secretos, conexión SSH, túneles, terminal, agente de nivel 3, Android y pipeline de release, sin pruebas dinámicas. Nueve hallazgos sensibles (3 de severidad alta, 3 media y 3 baja) que van a avisos privados, y cuatro tareas públicas de endurecimiento. Sigue En curso hasta dar de alta los avisos.'
Hallazgos privados: 9
Hallazgos públicos:
  - '[[Política de seguridad y avisos privados en GitHub]]'
  - '[[Endurecer la cadena de suministro]]'
  - '[[Actualizar sshj y BouncyCastle y retirar EdDSA-Java]]'
  - '[[Pedir desbloqueo o biometría para usar las credenciales]]'
Próxima auditoría: 2027-01-01
Fecha de creación: 2026-10-01T18:11:30+02:00
Última modificación: 2026-10-01T18:11:30+02:00
---

# Auditoría 2026-10-01 Completa

> Nota pública. Los hallazgos sensibles se describen **solo** en su aviso
> privado de GitHub (regla 5 del [[README]]). Aquí van como referencia opaca.

## Alcance y método

- **Versión**: `main` en `4691034` (`v0.1.0-beta.9` y dos commits más).
- **Áreas**:
  - conexión SSH y verificación de host;
  - autenticación (contraseña, clave software y clave hardware de Android);
  - `SecretStore` en Android y en escritorio;
  - persistencia de la configuración y de `known_hosts`;
  - túneles;
  - emulador de terminal y pegado;
  - scripts de inicio;
  - agente de nivel 3 (instalación, descarga, punto de encuentro, daemon y
    protocolo);
  - manifiesto y build de Android;
  - workflow de release y versiones de las dependencias.
- **Método**: lectura del código y de las ADR de seguridad, y contraste de las
  versiones de las dependencias con avisos públicos (CVE). **Sin pruebas
  dinámicas**: los puntos del nivel B que necesitan el servidor de pruebas, el
  emulador o herramientas (`ssh-audit -c`, MobSF, `gosec`, `osv-scanner`)
  quedan para la primera auditoría trimestral. Uno de los hallazgos está
  pendiente de confirmar con esas pruebas.
- **Hecha por**: un agente, a petición del usuario, en la misma sesión en que
  se creó [[Auditorías de seguridad]].

## Checklist ejecutado

Esta auditoría es anterior al checklist: lo originó. Cubre por lectura de
código los puntos A.4, B.2, B.3, B.5, B.6, B.7 (manifiesto) y B.9, y los C.1
a C.4 sin un modelo STRIDE formal. Quedan sin ejecutar A.1 a A.3, B.1, B.4,
B.7 (MobSF, copias de seguridad reales y capturas), B.8 y B.10, que toca
cubrir en la primera trimestral.

## Hallazgos privados

| Referencia | Severidad | Aviso | Estado |
| --- | --- | --- | --- |
| SEC-2026-01 | Alta | por crear | Abierto |
| SEC-2026-02 | Alta | por crear | Abierto |
| SEC-2026-03 | Alta | por crear | Abierto |
| SEC-2026-04 | Media | por crear | Abierto |
| SEC-2026-05 | Media | por crear | Abierto |
| SEC-2026-06 | Media | por crear | Abierto (pendiente de confirmar) |
| SEC-2026-07 | Baja | por crear | Abierto |
| SEC-2026-08 | Baja | por crear | Abierto |
| SEC-2026-09 | Baja | por crear | Abierto |

## Hallazgos públicos

- [[Política de seguridad y avisos privados en GitHub]]: falta `SECURITY.md`
  y un canal privado para avisar de fallos.
- [[Endurecer la cadena de suministro]]: sin avisos automáticos de
  dependencias, checksum del wrapper de Gradle, verificación de dependencias
  ni atestación de los artefactos.
- [[Actualizar sshj y BouncyCastle y retirar EdDSA-Java]]: librerías
  criptográficas atrasadas, una de ellas abandonada y con un CVE sin arreglo.
- [[Pedir desbloqueo o biometría para usar las credenciales]]: mejora
  opcional frente a quien tenga el dispositivo desbloqueado.

La firma del MSI ya tenía su tarea:
[[Firma de código Windows con SignPath Foundation]].

## Lo que está bien

- **Verificación de host TOFU**: nunca sobrescribe una clave ya confiada, y
  el panel del agente solo conecta a hosts ya confiados, sin preguntar.
- **Secretos**:
  - nunca están en `config.json`, solo sus referencias;
  - en Android van cifrados con AES-256-GCM con una clave no exportable del
    Keystore;
  - en escritorio, si no hay almacén del SO, la app se niega a guardar en vez
    de usar texto plano;
  - los nombres de referencia están limitados a `[A-Za-z0-9._-]`, así que no
    permiten salirse de su directorio.
- **Clave hardware**: no exportable, en StrongBox cuando existe.
- **Punto de encuentro del agente**:
  - escucha solo en `127.0.0.1`, con un token de 256 bits comparado en tiempo
    constante y exigido antes de cualquier dato;
  - el directorio de estado se rechaza si no es del usuario o si otros pueden
    acceder a él, o si es un enlace;
  - el fichero de estado es `0600`;
  - las tramas tienen un límite de 16 MB en los dos extremos, y hay un tiempo
    máximo hasta el `HELLO`.
- **Binario del agente**: se instala en el espacio del usuario con `0700` y
  se verifica su SHA-256 tras subirlo. Los binarios descargados se comprueban
  contra un SHA-256 fijado dentro de la app, nunca contra uno del propio
  origen de descarga.
- **Terminal**: solo responde a `DSR` y `DA`, y descarta las cadenas OSC y
  DCS, así que el servidor no puede hacerle escribir títulos ni otros textos
  en la entrada.
- **sshj ≥ 0.38**: incluye la mitigación de Terrapin (CVE-2023-48795) con el
  intercambio de claves estricto.
- **CI**:
  - acciones fijadas por SHA;
  - `permissions: contents: read` por defecto, y escritura solo en el job que
    publica;
  - secretos solo en GitHub Secrets.

## Cambios para el checklist

- El checklist de [[Auditorías de seguridad]] nace de esta auditoría.
- Las comprobaciones de regresión específicas de los nueve hallazgos privados
  se añaden cuando se publique cada aviso.
