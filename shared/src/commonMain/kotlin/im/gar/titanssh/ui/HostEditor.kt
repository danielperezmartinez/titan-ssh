package im.gar.titanssh.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import im.gar.titanssh.config.ConfigController
import im.gar.titanssh.config.Host
import im.gar.titanssh.config.HostAuth
import im.gar.titanssh.config.HostKeyPolicy
import im.gar.titanssh.config.Ids
import im.gar.titanssh.config.TerminalAppearance
import im.gar.titanssh.secret.SecretProvisioner
import im.gar.titanssh.secret.SecretRef
import im.gar.titanssh.secret.SshKeyType
import im.gar.titanssh.ssh.ensureHardwareKey
import im.gar.titanssh.ssh.isHardwareKeyProvisioningSupported
import im.gar.titanssh.theme.TitanColors
import im.gar.titanssh.theme.TitanDimens
import kotlinx.coroutines.launch

private enum class AuthKind(val label: String) {
    PASSWORD("Contraseña"),
    SOFTWARE_KEY("Clave (software)"),
    HARDWARE_KEY("Clave (hardware)"),
}

/**
 * Create/edit form for a [Host]. Captures where and how to connect, and — via
 * [provisioner] and the hardware-key seam — lets the user actually *create* the
 * auth material a method needs (task [[Gestión de claves y secretos UI]]):
 *
 * - **Password / software key**: the field holds the SecretStore *reference name*;
 *   a provisioning action writes the actual password / PEM key into the store
 *   under that reference (ADR-0001), so the config document keeps only the ref.
 * - **Hardware key**: the field holds the OS-key-store *alias*; a provisioning
 *   action generates (or reuses) the non-exportable key under it and shows its
 *   `authorized_keys` line to enroll on the server. The key never leaves the
 *   hardware (ADR-0005); on desktop v1 the action is unavailable.
 */
@Composable
fun HostEditor(
    controller: ConfigController,
    hostId: String?,
    provisioner: SecretProvisioner,
    onDone: () -> Unit,
) {
    val config by controller.state.collectAsState()
    val existing = remember(hostId) { config.hosts.firstOrNull { it.id == hostId } }

    var alias by remember { mutableStateOf(existing?.alias ?: "") }
    var hostname by remember { mutableStateOf(existing?.hostname ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(existing?.username ?: "") }

    var authKind by remember {
        mutableStateOf(
            when (existing?.auth) {
                is HostAuth.Password -> AuthKind.PASSWORD
                is HostAuth.SoftwareKey -> AuthKind.SOFTWARE_KEY
                is HostAuth.HardwareKey -> AuthKind.HARDWARE_KEY
                null -> AuthKind.HARDWARE_KEY
            },
        )
    }
    var keyType by remember {
        mutableStateOf(
            when (val a = existing?.auth) {
                is HostAuth.SoftwareKey -> a.keyType
                is HostAuth.HardwareKey -> a.keyType
                else -> SshKeyType.ED25519
            },
        )
    }
    var secretRef by remember {
        mutableStateOf(
            when (val a = existing?.auth) {
                is HostAuth.Password -> a.secretRef
                is HostAuth.SoftwareKey -> a.secretRef
                else -> ""
            },
        )
    }
    var passphraseRef by remember {
        mutableStateOf((existing?.auth as? HostAuth.SoftwareKey)?.passphraseRef ?: "")
    }
    var alias2 by remember { mutableStateOf((existing?.auth as? HostAuth.HardwareKey)?.alias ?: "") }

    var hostKeyPolicy by remember { mutableStateOf(existing?.hostKeyPolicy ?: HostKeyPolicy.TOFU) }
    var keepAlive by remember { mutableStateOf((existing?.keepAliveSeconds ?: 30).toString()) }
    var proxyJumpHostId by remember { mutableStateOf(existing?.proxyJumpHostId) }
    var groupId by remember { mutableStateOf(existing?.groupId) }
    var tags by remember { mutableStateOf(existing?.tags?.joinToString(", ") ?: "") }
    var marker by remember { mutableStateOf(existing?.marker ?: "[+]") }
    var fontSize by remember { mutableStateOf(existing?.appearance?.fontSize?.toString() ?: "") }

    // Provisioning-only state (never persisted to the config document).
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val hwSupported = remember { isHardwareKeyProvisioningSupported() }
    var busy by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var pemInput by remember { mutableStateOf("") }
    var passphraseInput by remember { mutableStateOf("") }
    var hwLine by remember { mutableStateOf("") }

    // Clear transient feedback when the method changes; each method owns its own.
    LaunchedEffect(authKind) { statusMsg = null }

    fun provision(successMessage: () -> String, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            statusMsg = null
            try {
                block()
                statusOk = true
                statusMsg = successMessage()
            } catch (e: Exception) {
                statusOk = false
                statusMsg = e.message ?: e::class.simpleName ?: "Error"
            } finally {
                busy = false
            }
        }
    }

    val portInt = port.toIntOrNull()
    val authValid = when (authKind) {
        AuthKind.PASSWORD -> secretRef.isNotBlank()
        AuthKind.SOFTWARE_KEY -> secretRef.isNotBlank()
        AuthKind.HARDWARE_KEY -> alias2.isNotBlank()
    }
    val canSave = hostname.isNotBlank() && username.isNotBlank() &&
        portInt != null && portInt in 1..65535 && authValid

    fun buildAuth(): HostAuth = when (authKind) {
        AuthKind.PASSWORD -> HostAuth.Password(secretRef.trim())
        AuthKind.SOFTWARE_KEY -> HostAuth.SoftwareKey(keyType, secretRef.trim(), passphraseRef.ifBlank { null })
        AuthKind.HARDWARE_KEY -> HostAuth.HardwareKey(keyType, alias2.trim())
    }

    fun save() {
        val id = existing?.id ?: Ids.host()
        controller.upsertHost(
            Host(
                id = id,
                alias = alias.trim(),
                hostname = hostname.trim(),
                port = portInt ?: 22,
                username = username.trim(),
                auth = buildAuth(),
                hostKeyPolicy = hostKeyPolicy,
                keepAliveSeconds = keepAlive.toIntOrNull() ?: 30,
                proxyJumpHostId = proxyJumpHostId,
                appearance = fontSize.toIntOrNull()?.let { TerminalAppearance(fontSize = it) },
                groupId = groupId,
                tags = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                marker = marker.ifBlank { "[+]" },
            ),
        )
        onDone()
    }

    EditorScaffold(
        title = if (existing == null) "Nuevo host" else "Editar host",
        onBack = onDone,
        onSave = { save() },
        canSave = canSave,
        onDelete = existing?.let { { controller.deleteHost(it.id); onDone() } },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bodyPadding())) {
            TitanTextField("Alias", alias, { alias = it }, placeholder = "servidor de casa")
            Gap()
            TitanTextField("Hostname / IP", hostname, { hostname = it }, placeholder = "192.168.1.10 o host.tailnet.ts.net")
            Gap()
            TitanTextField("Puerto", port, { port = it.filter(Char::isDigit) })
            Gap()
            TitanTextField("Usuario por defecto", username, { username = it }, placeholder = "root")

            SectionHeader("Autenticación")
            TitanSegmented("Método", AuthKind.entries, authKind, { authKind = it }, optionLabel = { it.label })
            Gap()
            when (authKind) {
                AuthKind.PASSWORD -> {
                    TitanTextField("Referencia de contraseña (SecretStore)", secretRef, { secretRef = it }, placeholder = "casa.password")
                    Gap()
                    TitanTextField("Contraseña (se guarda en el almacén, no en la config)", passwordInput, { passwordInput = it }, password = true)
                    Gap()
                    TitanButton(
                        "[ok] Guardar contraseña en el almacén",
                        onClick = {
                            provision({ "Contraseña guardada bajo '${secretRef.trim()}'." }) {
                                provisioner.savePassword(SecretRef(secretRef.trim()), passwordInput.toCharArray())
                                passwordInput = ""
                            }
                        },
                        kind = ButtonKind.PRIMARY,
                        enabled = !busy && secretRef.isNotBlank() && passwordInput.isNotBlank(),
                    )
                    ProvisionStatus(statusMsg, statusOk)
                }
                AuthKind.SOFTWARE_KEY -> {
                    TitanSegmented("Tipo de clave", SshKeyType.entries.toList(), keyType, { keyType = it }, optionLabel = { it.sshName })
                    Gap()
                    TitanTextField("Referencia de la clave privada (SecretStore)", secretRef, { secretRef = it }, placeholder = "casa.key")
                    Gap()
                    TitanTextField("Referencia de la passphrase (opcional)", passphraseRef, { passphraseRef = it })
                    Gap()
                    TitanTextField(
                        "Clave privada PEM (se guarda en el almacén)",
                        pemInput,
                        { pemInput = it },
                        placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----",
                        singleLine = false,
                    )
                    Gap()
                    TitanTextField("Passphrase de la clave (opcional)", passphraseInput, { passphraseInput = it }, password = true)
                    Gap()
                    TitanButton(
                        "[ok] Importar clave al almacén",
                        onClick = {
                            val trimmedPassRef = passphraseRef.trim()
                            provision({
                                "Clave privada importada bajo '${secretRef.trim()}'" +
                                    if (trimmedPassRef.isNotBlank() && passphraseInput.isNotBlank()) " (+ passphrase)." else "."
                            }) {
                                provisioner.importPrivateKey(
                                    keyRef = SecretRef(secretRef.trim()),
                                    pem = pemInput.toCharArray(),
                                    passphraseRef = trimmedPassRef.ifBlank { null }?.let { SecretRef(it) },
                                    passphrase = passphraseInput.ifBlank { null }?.toCharArray(),
                                )
                                pemInput = ""
                                passphraseInput = ""
                            }
                        },
                        kind = ButtonKind.PRIMARY,
                        enabled = !busy && secretRef.isNotBlank() && pemInput.isNotBlank(),
                    )
                    ProvisionStatus(statusMsg, statusOk)
                    Gap()
                    Caption("Se custodia solo la clave privada; enrola tú su .pub en el servidor. Generar un par nuevo llegará más adelante.")
                }
                AuthKind.HARDWARE_KEY -> {
                    TitanSegmented("Tipo de clave", listOf(SshKeyType.ECDSA_P256, SshKeyType.ED25519), keyType, { keyType = it }, optionLabel = { it.sshName })
                    Gap()
                    TitanTextField("Alias de la clave en el almacén del SO", alias2, { alias2 = it; hwLine = "" }, placeholder = "titan-hw-casa")
                    Gap()
                    Caption("La clave hardware no exportable no pasa por el SecretStore; se referencia por alias (ADR-0005).")
                    Gap()
                    TitanButton(
                        "[>] Generar / mostrar clave",
                        onClick = {
                            provision({ "Clave lista en el almacén del SO. Enrola la línea de abajo en authorized_keys." }) {
                                hwLine = ensureHardwareKey(alias2.trim())
                            }
                        },
                        kind = ButtonKind.PRIMARY,
                        enabled = hwSupported && !busy && alias2.isNotBlank(),
                    )
                    if (!hwSupported) {
                        Gap()
                        Caption(
                            "No disponible en escritorio: no hay clave hardware no exportable en v1. Usa una clave software.",
                            color = TitanColors.Warning,
                        )
                    }
                    ProvisionStatus(statusMsg, statusOk)
                    if (hwLine.isNotBlank()) {
                        Gap()
                        SelectionContainer {
                            Text(hwLine, style = MaterialTheme.typography.labelSmall, color = TitanColors.Body)
                        }
                        Gap()
                        TitanButton(
                            "[>] Copiar authorized_keys",
                            onClick = { clipboard.setText(AnnotatedString(hwLine)) },
                            kind = ButtonKind.SECONDARY,
                        )
                    }
                }
            }

            SectionHeader("Verificación de host y red")
            TitanSegmented("Política known_hosts", HostKeyPolicy.entries, hostKeyPolicy, { hostKeyPolicy = it }, optionLabel = { it.name })
            Gap()
            TitanTextField("Keepalive (segundos)", keepAlive, { keepAlive = it.filter(Char::isDigit) })
            Gap()
            val jumpOptions = config.hosts.filter { it.id != hostId }
            TitanDropdown(
                "ProxyJump (bastión, opcional)",
                options = jumpOptions,
                selected = jumpOptions.firstOrNull { it.id == proxyJumpHostId },
                onSelect = { proxyJumpHostId = if (proxyJumpHostId == it.id) null else it.id },
                optionLabel = { it.alias.ifBlank { it.hostname } },
                placeholder = "ninguno",
            )

            SectionHeader("Organización y apariencia")
            val groupOptions = config.groups
            TitanDropdown(
                "Grupo (proyecto)",
                options = groupOptions,
                selected = groupOptions.firstOrNull { it.id == groupId },
                onSelect = { groupId = if (groupId == it.id) null else it.id },
                optionLabel = { it.name },
                placeholder = "sin grupo",
            )
            Gap()
            TitanTextField("Etiquetas (separadas por coma)", tags, { tags = it }, placeholder = "prod, europa")
            Gap()
            TitanTextField("Marcador ASCII", marker, { marker = it }, placeholder = "[+]")
            Gap()
            TitanTextField("Tamaño de fuente del terminal (override, opcional)", fontSize, { fontSize = it.filter(Char::isDigit) })
            Spacer(Modifier.height(TitanDimens.SpaceSection))
        }
    }
}

/** Transient success/error line for a provisioning action (color = real state). */
@Composable
private fun ProvisionStatus(message: String?, ok: Boolean) {
    if (message != null) {
        Spacer(Modifier.height(TitanDimens.SpaceSm))
        Caption(message, color = if (ok) TitanColors.Success else TitanColors.Danger)
    }
}

@Composable
private fun Gap() {
    Spacer(Modifier.height(TitanDimens.SpaceMd))
}
