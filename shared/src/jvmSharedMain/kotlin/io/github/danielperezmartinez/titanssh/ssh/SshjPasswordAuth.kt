package io.github.danielperezmartinez.titanssh.ssh

import net.schmizz.sshj.userauth.method.ChallengeResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.PasswordUpdateProvider
import net.schmizz.sshj.userauth.password.Resource

/**
 * What happened while authenticating with a password, for [SshAuthFailed]: the
 * `keyboard-interactive` prompts that did not ask for the password (a second
 * factor, a one-time code) and whether the server asked to change an expired
 * password. As the [PasswordUpdateProvider] it declines that change.
 */
internal class PasswordAnswers : PasswordUpdateProvider {
    val unanswered = mutableListOf<String>()

    @Volatile
    var changeRequested = false
        private set

    override fun provideNewPassword(resource: Resource<*>?, prompt: String?): CharArray? {
        changeRequested = true
        return null
    }

    override fun shouldRetry(resource: Resource<*>?): Boolean = false
}

/**
 * Hands sshj a copy of [password] each time: `password` and then
 * `keyboard-interactive` both need it, and sshj blanks what it is given.
 */
internal class OneOffPassword(private val password: CharArray) : PasswordFinder {
    override fun reqPassword(resource: Resource<*>?): CharArray = password.copyOf()

    override fun shouldRetry(resource: Resource<*>?): Boolean = false
}

/**
 * Answers `keyboard-interactive` with [password], once, to a hidden prompt that
 * asks for it. Any other prompt gets an empty answer and is recorded in
 * [answers], so a refusal can tell a second factor from a wrong password.
 */
internal class PasswordPrompts(
    private val password: CharArray,
    private val answers: PasswordAnswers,
) : ChallengeResponseProvider {
    private var answered = false

    override fun getSubmethods(): List<String> = emptyList()

    override fun init(resource: Resource<*>?, name: String?, instruction: String?) = Unit

    override fun getResponse(prompt: String, echo: Boolean): CharArray {
        if (!echo && isPasswordPrompt(prompt)) {
            // Asked again: the password was wrong, and repeating it cannot help.
            if (answered) return CharArray(0)
            answered = true
            return password.copyOf()
        }
        answers.unanswered += prompt.trim()
        return CharArray(0)
    }

    override fun shouldRetry(): Boolean = false

    companion object {
        /** A PAM password prompt, in the languages a server is likely to speak. */
        private val PASSWORD_PROMPT =
            Regex("pass(word|wort|phrase)|contrase|mot de passe|senha|parola|wachtwoord|has[lł]o", RegexOption.IGNORE_CASE)

        /** A second factor that still says "password" ("One-time password:"). */
        private val SECOND_FACTOR = Regex("one[- ]time|otp|token|code|pin\\b", RegexOption.IGNORE_CASE)

        fun isPasswordPrompt(prompt: String): Boolean =
            PASSWORD_PROMPT.containsMatchIn(prompt) && !SECOND_FACTOR.containsMatchIn(prompt)
    }
}
