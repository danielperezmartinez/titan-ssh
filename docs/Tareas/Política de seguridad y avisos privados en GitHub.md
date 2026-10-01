---
Nombre: 'Política de seguridad y avisos privados en GitHub'
Estado: 'En curso'
Resumen: 'Preparar el repositorio para gestionar vulnerabilidades en privado, como pide la regla 5 del README: un SECURITY.md que explique cómo avisar de un fallo, activar el aviso privado de vulnerabilidades de GitHub, y dar de alta como borradores los avisos privados de la [[Auditoría 2026-10-01 Completa]]. Los avisos ya están creados (2026-10-01); faltan el SECURITY.md, activar el aviso privado de vulnerabilidades y revocar el token temporal.'
Decisiones: 'Decidido con el usuario el 2026-10-01 al crear el sistema [[Auditorías de seguridad]]: los hallazgos sensibles sin corregir viven solo en avisos privados de GitHub (uno por hallazgo), y en la bóveda solo como referencia opaca SEC-AAAA-NN.'
Bloqueada: []
Fecha de creación: 2026-10-01T18:11:30+02:00
Última modificación: 2026-10-01T18:40:00+02:00
---

# Política de seguridad y avisos privados en GitHub

## Objetivo

Que un fallo de seguridad, lo encuentre una auditoría propia o alguien de
fuera, tenga un camino privado desde el primer momento, sin pasar por issues
públicas ni por el repositorio. La norma está en la regla 5 del [[README]],
"Vulnerabilidades y hallazgos de seguridad".

## Criterios de finalización

- [ ] 👤 Activar *Private vulnerability reporting* en *Settings* → *Security*
  del repositorio.
- [ ] `SECURITY.md` en la raíz, en inglés como el `README.md`:
  - versiones con soporte (la última publicada);
  - cómo avisar: el botón *Report a vulnerability* de la pestaña *Security*,
    nunca una issue pública;
  - qué esperar: acuse de recibo, plazo orientativo y publicación coordinada
    del aviso al salir el arreglo.
- [x] Dar de alta como **borradores** los avisos privados de la
  [[Auditoría 2026-10-01 Completa]], y anotar el `GHSA-…` de cada uno en la
  tabla de esa auditoría. Hecho el 2026-10-01: el agente los creó con la API
  REST de GitHub, con un token temporal del usuario limitado a este
  repositorio y al permiso *Repository security advisories*.
- [x] Comprobar que sin permisos no se ven los borradores: la API sin
  autenticar devuelve una lista vacía y un 404 para el aviso.
- [ ] 👤 Borrar la variable de entorno del token y revocarlo.

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
