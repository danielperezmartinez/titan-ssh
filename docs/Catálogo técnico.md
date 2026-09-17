# Catálogo técnico

Este sistema permite descubrir la superficie reutilizable de titan-ssh antes de
buscar por todo el repositorio o crear una implementación nueva. Cada fila enlaza
con su nota estructurada; después de localizar una candidata se abre su
implementación, que continúa siendo la fuente de verdad técnica.

[[Catálogo técnico/Catálogo técnico.base|Abrir la vista completa del catálogo]]

## Cómo utilizarlo

1. Filtrar la vista por tipo, área, feature o estado.
2. Evitar las piezas `En revisión` salvo que el trabajo incluya estabilizarlas.
3. Abrir la fuente antes de depender de los detalles del contrato o modificarla.
4. Añadir o actualizar la entrada en el mismo cambio que altere una superficie
   reutilizable.

## Política de evolución

- Se amplía una pieza existente cuando el nuevo caso conserva su responsabilidad y
  un contrato claro.
- Se crea una pieza nueva solo cuando representa un patrón estable diferente y se
  registra en el catálogo en el mismo cambio.
- Las adaptaciones de datos de dominio se realizan en el consumidor; las
  primitivas compartidas no conocen modelos de un área.

## Vocabularios

Acordados al registrar la primera superficie reutilizable (`SecretStore`). Se
mantienen consistentes a partir de aquí; se amplían solo cuando aparezca un caso
que ninguno de los valores existentes cubra.

- **Tipo**: `Contrato` · `Servicio` · `Implementación de plataforma` ·
  `Modelo de dominio` · `Componente UI` · `Token` · `Utilidad`.
- **Área**: `Seguridad` · `Conexión SSH` · `Terminal` · `Configuración` ·
  `UI compartida` · `Plataforma`.
- **Feature**: `Custodia de credenciales` · `Autenticación` · `Conexión` ·
  `Resiliencia` · `Gestión de hosts` · `Shared UI`.
- **Ámbito**: `Aplicación` · `Feature` · `Shell`.

<!-- Opcional: si el proyecto tiene una comprobación automatizada del catálogo
(p. ej. un script que valida propiedades, fuentes y entrypoints), documéntala
aquí en una sección "Verificación automática". -->
