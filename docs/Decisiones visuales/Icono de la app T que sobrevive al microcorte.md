---
Nombre: 'Icono de la app: T que sobrevive al microcorte'
Estado: 'Aceptada'
Ámbito: 'Componente'
Resumen: 'El icono de titan-ssh es una T maciza de bloques rectos en ink (#fdfcfc) sobre el canvas (#201d1d). El tallo se corta y sigue en accent (#0a84ff): es el cursor que continúa tras un microcorte de red, el pilar principal del producto, y a la vez la inicial de titan. La fuente única es branding/icon.svg (solo rectángulos) y branding/RenderIcon.java genera todos los tamaños.'
Decisión: 'Adoptar el concepto C (T que sobrevive) entre tres bocetos: A [>] marcador ASCII, B >_ prompt y C T con el tallo cortado. Placa redondeada (rx 96 sobre 512) con hairline #3a3636 en escritorio; en Android la placa la pone la máscara del sistema.'
Consecuencias: 'El icono no es un motivo de terminal genérico (>_), así que no se confunde con otros clientes SSH. El acento solo aparece en el cursor, fiel a la disciplina de color (accent = foco/cursor). En el monocromo de Android el corte sigue contando la historia, sin el color. A 16 px el hueco apenas se ve, pero la T y el punto azul se leen.'
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-25T17:45:00+02:00
Última modificación: 2026-09-25T17:45:00+02:00
---

# Icono de la app: T que sobrevive al microcorte

Decidido con el usuario el 2026-09-25 dentro de
[[Icono y recursos gráficos de la app]]. Aplica los tokens de
[[Tokens visuales dark-first base opencode]] y la disciplina de color de
[[Vocabulario ASCII ampliado y disciplina de color]].

## Concepto

```
  ██████████      ink    #fdfcfc
      ██
      ██
                  <- microcorte (hueco)
      ▓▓          accent #0a84ff (el cursor que sigue)

  canvas #201d1d · hairline #3a3636
```

- Geometría plana y recta, sin degradados ni sombras. Encaja con el estilo
  mono-terminal de la UI (bloques de celda, radios mínimos, hairlines).
- **Descartados**: `[>]` (el marcador ASCII tal cual: coherente, pero menos
  distintivo) y `>_` con cursor de bloque (el motivo más habitual en clientes
  SSH).

## Fuente y generación

- Fuente vectorial única: `branding/icon.svg`. Por contrato solo contiene
  `<rect>`: `plate` es la placa y el resto es el glifo, centrado en (256, 256).
- `java branding/RenderIcon.java` (cualquier JDK 17+, sin dependencias)
  regenera todas las salidas, que se versionan:
  - `desktopApp/icons/titan-ssh.ico` (16–256) y `titan-ssh.png` (512) para
    los instaladores.
  - `desktopApp/src/main/resources/titan-ssh.png`: el icono de la ventana.
  - `androidApp/src/main/res/`: las capas del icono adaptativo (primer plano,
    fondo y monocromo). El glifo se escala para que su esquina más lejana caiga
    en el círculo de 66 dp de la zona segura.
  - `branding/generated/`: los PNG sueltos 16–512 (tema hicolor), el icono
    cuadrado de 512 para las tiendas y el gráfico de 1024×500.
- Los rectángulos del mismo color se unen en una sola forma, tanto en los raster
  como en los vectores de Android. Si se dibujaran por separado, el
  antialiasing dejaría una costura donde se tocan.
