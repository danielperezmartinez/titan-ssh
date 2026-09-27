# Servidor SSH de pruebas

Destino SSH desechable para probar titan-ssh a mano (emulador de Android, app
de escritorio) y para los tests de integración opcionales de Kotlin. Es un
Alpine con OpenSSH y bash, con el usuario `demo` (contraseña `demo`) y la
carpeta `~/proyecto`.

**Solo escucha en `127.0.0.1`.** Las credenciales son de prueba: no publiques
el puerto en la red.

## Crearlo (una vez)

```sh
docker build -t titan-test-sshd tools/test-sshd
docker run -d --name titan-test-sshd -p 127.0.0.1:2222:22 titan-test-sshd
```

## Usarlo

```sh
docker start titan-test-sshd   # encender
docker stop titan-test-sshd    # apagar
```

- Desde este equipo: `127.0.0.1:2222`.
- Desde el emulador de Android: `10.0.2.2:2222`.
- La clave de host se genera al construir la imagen y no cambia mientras no se
  reconstruya, así que la app no vuelve a pedir que la aceptes.

Para entrar con clave en vez de contraseña (p. ej. en los tests de
integración), copia una clave pública de pruebas:

```sh
docker cp <clave-de-pruebas>.pub titan-test-sshd:/home/demo/.ssh/authorized_keys
```

No sustituye a las pruebas contra destinos reales (otros sistemas, Windows,
redes reales): sirve para probar la app y su interfaz.
