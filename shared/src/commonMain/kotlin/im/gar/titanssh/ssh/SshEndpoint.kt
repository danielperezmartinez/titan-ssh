package im.gar.titanssh.ssh

/**
 * Where and as whom to open an SSH connection. Reusable host defaults and
 * per-session overrides (ADR mapping to the hosts/sessions model) resolve to one
 * of these before the engine connects.
 */
data class SshEndpoint(
    val host: String,
    val port: Int = 22,
    val username: String,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be in 1..65535, was $port" }
        require(username.isNotBlank()) { "username must not be blank" }
    }
}
