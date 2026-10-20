package io.github.danielperezmartinez.titanssh.update

import io.github.danielperezmartinez.titanssh.BuildInfo

/**
 * The updater of this build over [platform], or one that explains why it does
 * not run: a development build, a build without update keys, or an install
 * whose updates come from elsewhere ([unavailableReason]).
 */
internal fun buildUpdater(platform: () -> UpdatePlatform?, unavailableReason: () -> String?): AppUpdater {
    if (!BuildInfo.IS_RELEASE) return UnavailableUpdater("Las versiones de desarrollo no se actualizan.")
    val current = AppVersion.parse(BuildInfo.VERSION)
        ?: return UnavailableUpdater("La versión ${BuildInfo.VERSION} no admite actualizaciones.")
    val keys = UpdateEngine.decodeKeys(BuildInfo.UPDATE_KEYS)
    if (keys.isEmpty()) return UnavailableUpdater("Esta versión no trae la clave para verificar actualizaciones.")
    unavailableReason()?.let { return UnavailableUpdater(it) }
    val resolved = runCatching { platform() }.getOrNull()
        ?: return UnavailableUpdater("Esta instalación no se puede actualizar desde la app.")
    return UpdateEngine(current, BuildInfo.UPDATE_API, keys, resolved, UpdateChannel.of(BuildInfo.CHANNEL))
}
