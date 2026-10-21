package io.github.danielperezmartinez.titanssh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.danielperezmartinez.titanssh.AppChannel
import io.github.danielperezmartinez.titanssh.keyinstall.HostKeyQuestion
import io.github.danielperezmartinez.titanssh.keyinstall.KeyInstallResult
import io.github.danielperezmartinez.titanssh.keyinstall.KeyInstallStep
import io.github.danielperezmartinez.titanssh.keyinstall.StepState
import io.github.danielperezmartinez.titanssh.theme.TitanColors
import io.github.danielperezmartinez.titanssh.theme.TitanDimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Whether the host editor offers installing the key on the host (ADR-0030):
 * only the canary until it has been tried on real hosts.
 */
internal val keyInstallOffered: Boolean get() = AppChannel.isCanary

/**
 * Everything installing the key will do, for the text under the checkbox
 * (ADR-0030: full transparency before anything happens). [target] is
 * `user@host`; [hardware] says whether the key lives in the device's
 * security chip.
 */
internal fun keyInstallExplanation(target: String, hardware: Boolean): List<String> = listOf(
    if (hardware) {
        "Genera la clave en el chip de seguridad de este dispositivo, o usa la que ya haya con ese alias. No sale de él."
    } else {
        "Genera una clave ed25519 nueva y la guarda en el almacén de secretos del sistema, o usa la que ya haya con esa referencia."
    },
    "Te pide la contraseña de $target una sola vez. No se guarda: se usa para esta conexión y se borra de la memoria.",
    "Se conecta con esa contraseña y comprueba la identidad del servidor como siempre (te preguntará si es la primera vez). " +
        "Si el host salta por otro, pasa por él con las credenciales de ese host.",
    "Añade la línea pública de la clave a ~/.ssh/authorized_keys del host (en Windows, al fichero que use su OpenSSH: " +
        "administrators_authorized_keys si eres administrador). Crea la carpeta si no existe, ajusta sus permisos y no " +
        "la duplica si ya estaba. No toca nada más.",
    "Vuelve a conectar solo con la clave para comprobar que funciona.",
    "Si todo va bien, este host pasa a entrar con la clave. Si algo falla, no cambia nada aquí y te dice qué ha pasado.",
)

/** What the panel is showing. */
private sealed interface Phase {
    data object Idle : Phase
    data object AskingPassword : Phase
    data object Running : Phase
    data class Finished(val result: KeyInstallResult) : Phase
    data object Cancelled : Phase
}

/**
 * The "install the key on the host" part of the host editor's key sections
 * (ADR-0030): the checkbox, the explanation, the one-time password, the steps
 * as they run, a first-contact host key question and the summary.
 *
 * [run] does the work (prepare the key, install it, save the host on success)
 * and reports through the callbacks; the password it gets is wiped right after.
 * [previousPasswordRef] is a password the host held before, which the summary
 * offers to delete once the key works; [deletePassword] does that.
 */
@Composable
internal fun KeyInstallPanel(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    target: String,
    hardware: Boolean,
    canStart: Boolean,
    previousPasswordRef: String?,
    run: suspend (password: CharArray, askHostKey: (HostKeyQuestion) -> Unit, onStep: (KeyInstallStep, StepState) -> Unit) -> KeyInstallResult,
    deletePassword: suspend (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
    var password by remember { mutableStateOf("") }
    val steps = remember { mutableStateMapOf<KeyInstallStep, StepState>() }
    var question by remember { mutableStateOf<HostKeyQuestion?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var passwordDeleted by remember { mutableStateOf<Boolean?>(null) }
    // Leaving the editor stops an installation and never leaves a password behind.
    DisposableEffect(Unit) { onDispose { job?.cancel(); password = "" } }

    fun start() {
        val chars = password.toCharArray()
        password = ""
        steps.clear()
        passwordDeleted = null
        phase = Phase.Running
        job = scope.launch {
            try {
                val result = run(chars, { question = it }) { step, state -> steps[step] = state }
                phase = Phase.Finished(result)
            } catch (e: CancellationException) {
                phase = Phase.Cancelled
                throw e
            } finally {
                chars.fill(' ')
                question?.reject()
                question = null
            }
        }
    }

    TitanCheck("Instalar la clave en el host por mí", checked, onCheckedChange = { if (phase != Phase.Running) onCheckedChange(it) })
    if (!checked) return

    Spacer(Modifier.height(TitanDimens.SpaceSm))
    Caption("Al pulsar «Generar e instalar», titan-ssh:")
    keyInstallExplanation(target, hardware).forEachIndexed { i, text ->
        Spacer(Modifier.height(TitanDimens.SpaceXs))
        Caption("${i + 1}. $text")
    }
    Spacer(Modifier.height(TitanDimens.SpaceXs))
    Caption(
        "Se guarda: la clave (en el almacén del sistema) y la huella del servidor (known_hosts). La contraseña, nunca.",
        color = TitanColors.Body,
    )
    Spacer(Modifier.height(TitanDimens.SpaceMd))

    when (val current = phase) {
        Phase.AskingPassword -> {
            TitanTextField(
                "Contraseña de $target (solo para esta vez)",
                password,
                { password = it },
                password = true,
                onDone = { if (password.isNotEmpty()) start() },
            )
            Spacer(Modifier.height(TitanDimens.SpaceSm))
            Row(horizontalArrangement = Arrangement.spacedBy(TitanDimens.SpaceSm)) {
                TitanButton("[ok] Continuar", onClick = { start() }, kind = ButtonKind.PRIMARY, enabled = password.isNotEmpty())
                TitanButton("[x] Cancelar", onClick = { password = ""; phase = Phase.Idle })
            }
        }
        Phase.Running -> {
            StepList(steps)
            question?.let { q ->
                Spacer(Modifier.height(TitanDimens.SpaceSm))
                HostKeyPromptBar(
                    fingerprint = q.info.fingerprintSha256,
                    keyType = q.info.keyType,
                    host = q.jumpHost?.let { "el salto $it" } ?: "${q.info.host}:${q.info.port}",
                    onAccept = { q.accept(); question = null },
                    onReject = { q.reject(); question = null },
                )
            }
            Spacer(Modifier.height(TitanDimens.SpaceSm))
            TitanButton("[x] Cancelar", onClick = { job?.cancel() })
        }
        else -> {
            if (current is Phase.Finished || current is Phase.Cancelled) {
                StepList(steps)
                Spacer(Modifier.height(TitanDimens.SpaceSm))
            }
            when (current) {
                is Phase.Finished -> Summary(current.result, previousPasswordRef, passwordDeleted) {
                    scope.launch {
                        passwordDeleted = runCatching { deletePassword(it) }.isSuccess
                    }
                }
                Phase.Cancelled -> Caption(
                    "Cancelado. El host no ha cambiado; si ya se había escrito la clave en el servidor, repetirlo no la duplica.",
                    color = TitanColors.Warning,
                )
                else -> Unit
            }
            Spacer(Modifier.height(TitanDimens.SpaceSm))
            val again = current is Phase.Finished && current.result is KeyInstallResult.Failed || current is Phase.Cancelled
            TitanButton(
                if (again) "[>] Volver a intentarlo" else "[>] Generar e instalar",
                onClick = { phase = Phase.AskingPassword },
                kind = ButtonKind.PRIMARY,
                enabled = canStart,
            )
            if (!canStart) {
                Spacer(Modifier.height(TitanDimens.SpaceXs))
                Caption("Rellena el host, el puerto, el usuario y la referencia o el alias de la clave.", color = TitanColors.Warning)
            }
        }
    }
}

@Composable
private fun StepList(steps: Map<KeyInstallStep, StepState>) {
    Column {
        KeyInstallStep.entries.forEach { step ->
            val state = steps[step]
            val (mark, color) = when (state) {
                StepState.RUNNING -> "[..]" to TitanColors.Accent
                StepState.DONE -> "[ok]" to TitanColors.Success
                StepState.FAILED -> "[x] " to TitanColors.Danger
                null -> "[  ]" to TitanColors.Stone
            }
            Row {
                Text(mark, style = MaterialTheme.typography.labelSmall, color = color)
                Spacer(Modifier.width(TitanDimens.SpaceSm))
                Text(step.label, style = MaterialTheme.typography.labelSmall, color = if (state == null) TitanColors.Mute else TitanColors.Body)
            }
        }
    }
}

@Composable
private fun Summary(
    result: KeyInstallResult,
    previousPasswordRef: String?,
    passwordDeleted: Boolean?,
    onDeletePassword: (String) -> Unit,
) {
    when (result) {
        is KeyInstallResult.Installed -> {
            Caption(
                (if (result.alreadyPresent) "La clave ya estaba en " else "Clave añadida a ") +
                    "${result.file} (${result.system}) y comprobada: este host entra ya con la clave.",
                color = TitanColors.Success,
            )
            result.notes.forEach {
                Spacer(Modifier.height(TitanDimens.SpaceXs))
                Caption(it, color = TitanColors.Warning)
            }
            if (previousPasswordRef != null) {
                Spacer(Modifier.height(TitanDimens.SpaceSm))
                when (passwordDeleted) {
                    true -> Caption("Contraseña guardada '$previousPasswordRef' borrada del almacén.", color = TitanColors.Success)
                    false -> Caption("No se ha podido borrar la contraseña '$previousPasswordRef'.", color = TitanColors.Danger)
                    null -> {
                        Caption("El host tenía una contraseña guardada ('$previousPasswordRef'). Ya no la necesita.")
                        Spacer(Modifier.height(TitanDimens.SpaceXs))
                        TitanButton(
                            "[x] Borrar la contraseña guardada",
                            onClick = { onDeletePassword(previousPasswordRef) },
                            kind = ButtonKind.DANGER,
                        )
                    }
                }
            }
        }
        is KeyInstallResult.Failed -> {
            Text(
                "[!] ${result.problem.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = TitanColors.Danger,
            )
            Spacer(Modifier.height(TitanDimens.SpaceXs))
            Caption(result.problem.advice, color = TitanColors.Body)
            result.detail?.let {
                Spacer(Modifier.height(TitanDimens.SpaceXs))
                Caption(it)
            }
            Spacer(Modifier.height(TitanDimens.SpaceXs))
            Caption(
                if (result.step == KeyInstallStep.KEY || result.step == KeyInstallStep.CONNECT && !result.problem.passwordSent) {
                    "La contraseña no ha llegado a enviarse. El host no ha cambiado."
                } else {
                    "El host no ha cambiado."
                },
            )
        }
    }
}
