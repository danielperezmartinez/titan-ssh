package io.github.danielperezmartinez.titanssh.terminal

import io.github.danielperezmartinez.titanssh.ssh.SshExecChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** An `exec` channel answering [ShellSyntax.PROBE] with [output] (empty: a POSIX shell). */
internal class FakeProbeChannel(output: String = "") : SshExecChannel {
    override val output: Flow<ByteArray> = flowOf(output.encodeToByteArray())
    override val errors: Flow<ByteArray> = emptyFlow()
    override suspend fun send(data: ByteArray) {}
    override suspend fun close(): Int? = 0
}
