---
Nombre: 'Política de seguridad y avisos privados en GitHub'
Estado: 'Pendiente'
Resumen: 'Preparar el repositorio para gestionar vulnerabilidades en privado, como pide la regla 5 del README: un SECURITY.md que explique cómo avisar de un fallo, activar el aviso privado de vulnerabilidades de GitHub, y dar de alta como borradores los avisos privados de la [[Auditoría 2026-10-01 Completa]]. Lo de GitHub lo hace el usuario.'
Decisiones: 'Decidido con el usuario el 2026-10-01 al crear el sistema [[Auditorías de seguridad]]: los hallazgos sensibles sin corregir viven solo en avisos privados de GitHub (uno por hallazgo), y en la bóveda solo como referencia opaca SEC-AAAA-NN.'
Bloqueada: []
Fecha de creación: 2026-10-01T18:11:30+02:00
Última modificación: 2026-10-01T18:11:30+02:00
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
- [ ] 👤 Dar de alta como **borradores** los avisos privados de la
  [[Auditoría 2026-10-01 Completa]], a partir de los borradores que prepara el
  agente. Se anota el `GHSA-…` de cada uno en la tabla de esa auditoría.
- [ ] Comprobar que un usuario sin permisos no ve los borradores (sesión
  privada del navegador).

## Verificación

<Se rellena al completar.>

## Resultado

<Se rellena al completar.>
