# Política de seguridad

titan-ssh guarda credenciales SSH y mantiene sesiones abiertas en los equipos
a los que se conecta, así que tomamos en serio cualquier fallo de seguridad.
Gracias por avisarnos en privado.

> **English:** please report vulnerabilities privately through
> [Report a vulnerability](https://github.com/danielperezmartinez/titan-ssh/security/advisories/new)
> (GitHub private vulnerability reporting). Do not open public issues, pull
> requests or discussions about them. Reports in English are welcome.

## Versiones con soporte

Solo se corrige la **última versión publicada** en
[Releases](https://github.com/danielperezmartinez/titan-ssh/releases).
Mientras no haya una versión estable, eso incluye la última versión de prueba
(`-beta.N`). Los arreglos salen en una versión nueva; no se publican parches
para versiones anteriores.

## Cómo informar de una vulnerabilidad

Usa el botón **Report a vulnerability** de la pestaña
[Security](https://github.com/danielperezmartinez/titan-ssh/security) del
repositorio. Abre un aviso privado que solo ven los mantenedores.

**No** la publiques en issues, pull requests, discusiones, commits ni redes
sociales hasta que haya una versión con el arreglo.

Ayuda mucho que el informe incluya:

- la versión de titan-ssh y la plataforma (Android, Windows o Linux), y, si
  afecta al agente, el sistema del destino;
- qué componente falla (conexión SSH, verificación del servidor, almacén de
  credenciales, túneles, terminal, scripts de inicio, `titan-agent`, paquetes
  o pipeline de publicación);
- cómo reproducirlo, paso a paso, y qué impacto tiene;
- si lo conoces, cómo arreglarlo.

## Qué puedes esperar

titan-ssh es un proyecto personal sin financiación y no se compromete a plazos
fijos. Cada aviso se lee, se valora y se responde en el propio aviso en cuanto
es posible, y los más graves pasan por delante del resto del trabajo.

- **Publicación coordinada**: el aviso se publica (con CVE si procede) junto con
  la versión que lo corrige, y se te reconoce en él si quieres.

No hay programa de recompensas.

## Alcance

Dentro:

- la app de titan-ssh para Android, Windows y Linux;
- `titan-agent`, el agente que la app instala en el destino para mantener las
  sesiones (directorio `agent/`);
- los paquetes publicados y el pipeline que los genera
  (`.github/workflows/`).

Fuera:

- fallos del servidor SSH del destino o del sistema operativo;
- ataques que necesitan un destino ya comprometido (por ejemplo, con acceso
  de root);
- ataques que necesitan acceso físico a un dispositivo desbloqueado;
- fallos de dependencias de terceros que no afecten a titan-ssh; esos se
  comunican al proyecto correspondiente.

## Buena fe

No emprenderemos acciones contra quien investigue y avise de buena fe:
pruebas solo contra equipos y cuentas propias, sin acceder a datos de otras
personas, sin degradar servicios de terceros y sin hacer nada público hasta
acordarlo en el aviso.

## Comprobar las descargas

Cada Release trae un `SHA256SUMS` para comprobar los ficheros descargados. La
huella del certificado con el que se firman todas las APK está en el
[README](README.md#android).
