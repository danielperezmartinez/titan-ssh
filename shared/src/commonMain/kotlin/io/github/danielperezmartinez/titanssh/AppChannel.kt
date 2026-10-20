package io.github.danielperezmartinez.titanssh

/**
 * Which app this build is (ADR-0027): the stable one or the canary installed
 * next to it. Every per-app name derives from [appName], so the canary never
 * shares a folder, a secret namespace or a remote agent with the stable app.
 */
object AppChannel {
    /** True for the canary app ([BuildInfo.CHANNEL]). */
    val isCanary: Boolean = BuildInfo.CHANNEL == "canary"

    /** The stable app's name: base of its folders, secret namespace and agent dirs. */
    const val STABLE_APP_NAME: String = "titan-ssh"

    /**
     * `titan-ssh`, or `titan-ssh-canary` for the canary: the local data and
     * cache folders, the secret namespace and the agent's folders on a
     * destination. The Go agent gets the same name from the channel the build
     * stamps into it (`-X main.channel`).
     */
    val appName: String = if (isCanary) "$STABLE_APP_NAME-canary" else STABLE_APP_NAME

    /** The name shown to the user: `titan-ssh` or `titan-ssh canary`. */
    val displayName: String = if (isCanary) "$STABLE_APP_NAME canary" else STABLE_APP_NAME
}
