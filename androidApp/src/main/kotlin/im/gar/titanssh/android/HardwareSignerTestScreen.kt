package im.gar.titanssh.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import im.gar.titanssh.ssh.AndroidHardwareKeys
import im.gar.titanssh.ssh.SshCredentials
import im.gar.titanssh.ssh.SshEndpoint
import im.gar.titanssh.ssh.createSshConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only screen to verify the hardware-backed SSH signer on a real device:
 * generate a non-exportable key in the Android Keystore, show its public line to
 * enroll on a test host, then connect using [SshCredentials.HardwareKey] so the
 * signing happens in hardware. Not part of the product UI.
 */
@Composable
fun HardwareSignerTestScreen() {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("") }
    var pubKeyLine by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val alias = "titan-ssh-test"
    fun log(line: String) {
        log = (log + "\n" + line).trim()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("titan-ssh · prueba signer hardware", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(host, { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it }, label = { Text("Puerto") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it }, label = { Text("Usuario") }, modifier = Modifier.fillMaxWidth())

        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    busy = true
                    try {
                        val line = withContext(Dispatchers.IO) { AndroidHardwareKeys.ensureKey(alias) }
                        pubKeyLine = line
                        log("Clave hardware lista. Añade esta línea a ~/.ssh/authorized_keys del host:")
                    } catch (e: Exception) {
                        log("Error generando clave: ${e::class.simpleName}: ${e.message}")
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text("1. Generar / mostrar clave hardware") }

        if (pubKeyLine.isNotBlank()) {
            SelectionContainer { Text(pubKeyLine, style = MaterialTheme.typography.bodySmall) }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { clipboard.setText(AnnotatedString(pubKeyLine)) },
            ) { Text("Copiar clave al portapapeles") }
        }

        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    busy = true
                    log("Conectando a $user@$host:${port} con clave hardware…")
                    try {
                        val session = createSshConnector().connect(
                            endpoint = SshEndpoint(host, port.toIntOrNull() ?: 22, user),
                            credentials = SshCredentials.HardwareKey(alias),
                            hostKeyVerifier = { info ->
                                log("Host key ${info.keyType} ${info.fingerprintSha256}")
                                true
                            },
                            keepAliveSeconds = 15,
                        )
                        log("Autenticado con la clave del Keystore. Abriendo shell…")
                        val shell = session.openShell()
                        val out = StringBuilder()
                        val reader = launch(Dispatchers.IO) {
                            shell.output.collect { out.append(it.decodeToString()) }
                        }
                        shell.send("whoami\n".encodeToByteArray())
                        delay(2500)
                        shell.send("exit\n".encodeToByteArray())
                        delay(300)
                        reader.cancel()
                        shell.close()
                        session.close()
                        log("Salida shell: ${out.toString().trim().take(300)}")
                        log("✅ OK: firma en hardware verificada")
                    } catch (e: Exception) {
                        val chain = generateSequence(e as Throwable?) { it.cause }
                            .joinToString("\n  ⇐ ") { "${it::class.simpleName}: ${it.message}" }
                        log("❌ $chain")
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text("2. Conectar y probar (HardwareKey)") }

        if (busy) CircularProgressIndicator()

        if (log.isNotBlank()) {
            SelectionContainer { Text(log, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
