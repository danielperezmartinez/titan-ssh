package im.gar.titanssh.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import im.gar.titanssh.config.ConfigController
import im.gar.titanssh.config.Host
import im.gar.titanssh.config.HostAuth
import im.gar.titanssh.config.HostKeyPolicy
import im.gar.titanssh.config.Ids
import im.gar.titanssh.config.TerminalAppearance
import im.gar.titanssh.secret.SshKeyType
import im.gar.titanssh.theme.TitanDimens

private enum class AuthKind(val label: String) {
    PASSWORD("Contraseña"),
    SOFTWARE_KEY("Clave (software)"),
    HARDWARE_KEY("Clave (hardware)"),
}

/**
 * Create/edit form for a [Host]. Captures where and how to connect. Secret
 * material is never entered here: for password/software auth the field is the
 * SecretStore *reference name*; for hardware auth it is the key-store alias.
 */
@Composable
fun HostEditor(controller: ConfigController, hostId: String?, onDone: () -> Unit) {
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
                AuthKind.PASSWORD ->
                    TitanTextField("Referencia de contraseña (SecretStore)", secretRef, { secretRef = it }, placeholder = "casa.password")
                AuthKind.SOFTWARE_KEY -> {
                    TitanSegmented("Tipo de clave", SshKeyType.entries.toList(), keyType, { keyType = it }, optionLabel = { it.sshName })
                    Gap()
                    TitanTextField("Referencia de la clave privada (SecretStore)", secretRef, { secretRef = it }, placeholder = "casa.key")
                    Gap()
                    TitanTextField("Referencia de la passphrase (opcional)", passphraseRef, { passphraseRef = it })
                }
                AuthKind.HARDWARE_KEY -> {
                    TitanSegmented("Tipo de clave", listOf(SshKeyType.ECDSA_P256, SshKeyType.ED25519), keyType, { keyType = it }, optionLabel = { it.sshName })
                    Gap()
                    TitanTextField("Alias de la clave en el almacén del SO", alias2, { alias2 = it }, placeholder = "titan-hw-casa")
                    Gap()
                    Caption("La clave hardware no exportable no pasa por el SecretStore; se referencia por alias (ADR-0005).")
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

@Composable
private fun Gap() {
    Spacer(Modifier.height(TitanDimens.SpaceMd))
}
