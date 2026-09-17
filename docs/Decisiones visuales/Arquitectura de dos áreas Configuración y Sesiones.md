---
Nombre: Arquitectura de dos áreas (Configuración y Sesiones)
Estado: Aceptada
Ámbito: Layout
Resumen: 'La app se organiza en dos áreas diferenciadas: Configuración (gestión de hosts, sesiones y scripts de inicio) y Sesiones (pestañas con las sesiones abiertas y su terminal). Las sesiones se lanzan a partir de las configuraciones guardadas.'
Decisión: Separar la app en un área de Configuración y un área de Sesiones, con hosts reutilizables y sesiones que los referencian.
Consecuencias: Un host describe "dónde y cómo conectar" (reutilizable); una sesión describe "qué hacer al conectar" (scripts, directorio inicial, resiliencia) y referencia un host.
Reemplaza: []
Reemplazada por: []
Fecha de creación: 2026-09-17T16:44:06+02:00
Última modificación: 2026-09-17T16:44:06+02:00
---

# Arquitectura de dos áreas (Configuración y Sesiones)

## Contexto

titan-ssh no imita la gestión de configuraciones de Termius. Se busca un modelo
propio y claro. El usuario trabaja con varios proyectos y necesita separar la
preparación (hosts, sesiones, scripts) del trabajo en vivo (terminales abiertos).

## Decisión

Dos áreas bien diferenciadas:

1. **Configuración.** Gestión y persistencia de:
   - **Hosts** guardados por separado (reutilizables).
   - **Sesiones**, que reutilizan un host almacenado y añaden un formulario guiado
     de scripts (inicio, post-inicio, etc.) reordenables, más su configuración
     propia.
2. **Sesiones.** Pestañas con las sesiones abiertas, lanzadas a partir de las
   configuraciones guardadas: cambiar, cerrar y mover pestañas; al estar en una
   pestaña se ve el terminal listo para trabajar.

Principio de separación host/sesión: el **host** describe "dónde y cómo conectar"
(reutilizable); la **sesión** describe "qué hacer al conectar" y referencia un
host, pudiendo sobreescribir valores por defecto del host.

## Alternativas consideradas

- Modelo estilo Termius — descartado explícitamente por no convencer al usuario.

## Consecuencias

- Positivas: reutilización de hosts, sesiones como "recetas" de trabajo,
  separación clara entre preparar y trabajar.
- Negativas / compromisos: hay que definir bien qué configuración vive en el host
  y qué en la sesión (herencia y override).

Tareas relacionadas: [[Panel de gestión de hosts y sesiones]],
[[Scripts de inicio por sesión]], [[Terminal multipestaña con sesiones simultáneas]].
