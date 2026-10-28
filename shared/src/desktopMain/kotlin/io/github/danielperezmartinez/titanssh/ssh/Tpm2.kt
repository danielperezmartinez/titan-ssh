package io.github.danielperezmartinez.titanssh.ssh

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * One way to reach a TPM 2.0: send a command, get its response. Linux's
 * `/dev/tpmrm0` ([Tpm2Device]) in the app; swtpm over TCP in tests.
 */
internal interface Tpm2Transport : Closeable {
    fun transmit(command: ByteArray): ByteArray
}

/**
 * The kernel's TPM resource manager. It keeps each open file's objects apart
 * from other programs' and flushes them when the file closes.
 */
internal class Tpm2Device(path: File = File(DEFAULT_PATH)) : Tpm2Transport {
    private val file = RandomAccessFile(path, "rw")

    override fun transmit(command: ByteArray): ByteArray {
        file.write(command)
        // The kernel hands back the whole response in one read.
        val buffer = ByteArray(MAX_RESPONSE)
        val n = file.read(buffer)
        check(n >= HEADER_SIZE) { "Short TPM response ($n bytes)" }
        return buffer.copyOf(n)
    }

    override fun close() = file.close()

    companion object {
        const val DEFAULT_PATH = "/dev/tpmrm0"
        private const val MAX_RESPONSE = 4096
    }
}

/** A TPM simulator's command port (swtpm `--server type=tcp`), for tests. */
internal class Tpm2Socket(host: String, port: Int) : Tpm2Transport {
    private val socket = Socket().apply { connect(InetSocketAddress(host, port), 5_000) }

    override fun transmit(command: ByteArray): ByteArray {
        socket.getOutputStream().apply { write(command); flush() }
        val input = DataInputStream(socket.getInputStream())
        val header = ByteArray(HEADER_SIZE).also(input::readFully)
        val size = ByteBuffer.wrap(header, 2, 4).int
        val rest = ByteArray(size - HEADER_SIZE).also(input::readFully)
        return header + rest
    }

    override fun close() = socket.close()
}

/** A TPM command that failed, with its response code. */
internal class Tpm2Exception(val command: String, val rc: Int) :
    Exception("TPM2_$command failed: 0x${rc.toString(16)}") {
    /** Wrong or missing authorization (TPM_RC_AUTH_FAIL / TPM_RC_BAD_AUTH, any session). */
    val isAuthFailure: Boolean
        get() = rc and RC_FMT1 != 0 && (rc and 0x3F).let { it == 0x0E || it == 0x22 }
}

/** The parts of an EC P-256 signing key that the TPM wraps: safe to keep on disk, usable only on that TPM. */
internal class Tpm2KeyBlob(val private: ByteArray, val public: ByteArray) {
    /** The public point (x, y) from the key's public area. */
    fun eccPoint(): Pair<ByteArray, ByteArray> {
        val b = ByteBuffer.wrap(public)
        b.short // type
        b.short // nameAlg
        b.int // objectAttributes
        b.skipSized() // authPolicy
        if (b.short.toInt() != ALG_NULL) b.position(b.position() + 4) // symmetric keyBits + mode
        if (b.short.toInt() != ALG_NULL) b.short // scheme hashAlg
        b.short // curveID
        if (b.short.toInt() != ALG_NULL) b.short // kdf hashAlg
        return b.sized() to b.sized()
    }

    /** `TITANTPM1`, then each part as a TPM2B (u16 size + bytes). */
    fun encode(): ByteArray = Tpm2Command.Writer().apply {
        bytes(MAGIC); sized(private); sized(public)
    }.toByteArray()

    companion object {
        private val MAGIC = "TITANTPM1".encodeToByteArray()

        fun decode(data: ByteArray): Tpm2KeyBlob {
            val b = ByteBuffer.wrap(data)
            val magic = ByteArray(MAGIC.size).also(b::get)
            require(magic.contentEquals(MAGIC)) { "Not a titan-ssh TPM key file" }
            return Tpm2KeyBlob(b.sized(), b.sized())
        }
    }
}

/**
 * The few TPM 2.0 commands an SSH signing key needs (TCG TPM 2.0 Library,
 * Part 3): create the storage key, create and load an EC P-256 signing key
 * under it, sign, flush. Authorizations are plain empty passwords: the keys
 * have no auth value of their own (ADR-0033).
 */
internal class Tpm2(private val transport: Tpm2Transport) {

    /**
     * The owner hierarchy's storage key from the TCG standard SRK template, the
     * same key `0x81000001` holds where something made it persistent. The TPM
     * derives it from its seed, so it is the same key every time. If the owner
     * hierarchy has a password, falls back to that persistent copy.
     *
     * @return the handle and whether it must be flushed afterwards.
     */
    fun storageKey(): Pair<Int, Boolean> = try {
        val cmd = Tpm2Command(CC_CREATE_PRIMARY, "CreatePrimary", sessions = true).apply {
            handle(RH_OWNER)
            emptyPasswordSession()
            sensitiveCreate()
            sized(srkTemplate())
            u16(0) // outsideInfo
            u32(0) // creationPCR
        }
        ByteBuffer.wrap(run(cmd)).int to true
    } catch (e: Tpm2Exception) {
        if (!e.isAuthFailure) throw e
        SRK_PERSISTENT to false
    }

    /** Creates a new EC P-256 signing key under [parent]; the TPM returns it wrapped. */
    fun createSigningKey(parent: Int): Tpm2KeyBlob {
        val cmd = Tpm2Command(CC_CREATE, "Create", sessions = true).apply {
            handle(parent)
            emptyPasswordSession()
            sensitiveCreate()
            sized(signingKeyTemplate())
            u16(0) // outsideInfo
            u32(0) // creationPCR
        }
        val b = ByteBuffer.wrap(run(cmd))
        b.int // parameterSize
        return Tpm2KeyBlob(private = b.sized(), public = b.sized())
    }

    /** Loads [key] under [parent] and returns its transient handle. */
    fun load(parent: Int, key: Tpm2KeyBlob): Int {
        val cmd = Tpm2Command(CC_LOAD, "Load", sessions = true).apply {
            handle(parent)
            emptyPasswordSession()
            sized(key.private)
            sized(key.public)
        }
        return ByteBuffer.wrap(run(cmd)).int
    }

    /** ECDSA-SHA256 signature of [digest] (32 bytes) with the loaded key [handle], as (r, s). */
    fun sign(handle: Int, digest: ByteArray): Pair<ByteArray, ByteArray> {
        val cmd = Tpm2Command(CC_SIGN, "Sign", sessions = true).apply {
            handle(handle)
            emptyPasswordSession()
            sized(digest)
            u16(ALG_ECDSA); u16(ALG_SHA256) // inScheme
            // validation: a null ticket, fine for a key that is not restricted.
            u16(ST_HASHCHECK); u32(RH_NULL); u16(0)
        }
        val b = ByteBuffer.wrap(run(cmd))
        b.int // parameterSize
        val sigAlg = b.short.toInt()
        check(sigAlg == ALG_ECDSA) { "Unexpected TPM signature algorithm 0x${sigAlg.toString(16)}" }
        b.short // hash
        return b.sized() to b.sized()
    }

    fun flush(handle: Int) {
        run(Tpm2Command(CC_FLUSH_CONTEXT, "FlushContext", sessions = false).apply { u32(handle) })
    }

    /** The response after its header, or [Tpm2Exception] when the TPM refuses. */
    private fun run(cmd: Tpm2Command): ByteArray {
        val response = transport.transmit(cmd.encode())
        val b = ByteBuffer.wrap(response)
        b.short // tag
        val size = b.int
        val rc = b.int
        if (rc != 0) throw Tpm2Exception(cmd.name, rc)
        return response.copyOfRange(HEADER_SIZE, size)
    }

    private companion object {
        const val CC_CREATE_PRIMARY = 0x131
        const val CC_CREATE = 0x153
        const val CC_LOAD = 0x157
        const val CC_SIGN = 0x15D
        const val CC_FLUSH_CONTEXT = 0x165

        const val RH_OWNER = 0x40000001
        const val RH_NULL = 0x40000007
        const val SRK_PERSISTENT = 0x81000001.toInt()

        const val ST_HASHCHECK = 0x8024
        const val ALG_ECC = 0x0023
        const val ALG_AES = 0x0006
        const val ALG_CFB = 0x0043
        const val ECC_NIST_P256 = 0x0003

        // TPMA_OBJECT bits.
        const val FIXED_TPM = 1 shl 1
        const val FIXED_PARENT = 1 shl 4
        const val SENSITIVE_DATA_ORIGIN = 1 shl 5
        const val USER_WITH_AUTH = 1 shl 6
        const val NO_DA = 1 shl 10
        const val RESTRICTED = 1 shl 16
        const val DECRYPT = 1 shl 17
        const val SIGN = 1 shl 18

        /** TCG "Provisioning Guidance" ECC P-256 SRK template, zero-filled unique as it specifies. */
        fun srkTemplate(): ByteArray = Tpm2Command.Writer().apply {
            u16(ALG_ECC); u16(ALG_SHA256)
            u32(FIXED_TPM or FIXED_PARENT or SENSITIVE_DATA_ORIGIN or USER_WITH_AUTH or NO_DA or RESTRICTED or DECRYPT)
            u16(0) // authPolicy
            u16(ALG_AES); u16(128); u16(ALG_CFB) // symmetric
            u16(ALG_NULL) // scheme
            u16(ECC_NIST_P256)
            u16(ALG_NULL) // kdf
            sized(ByteArray(32)); sized(ByteArray(32)) // unique
        }.toByteArray()

        /**
         * An EC P-256 key that only signs, with ECDSA-SHA256, and never leaves
         * this TPM (fixedTPM, fixedParent). No dictionary-attack protection: it
         * has no password to guess, and a lockout would also lock the user's
         * other TPM keys (a disk unlocked with a PIN, say).
         */
        fun signingKeyTemplate(): ByteArray = Tpm2Command.Writer().apply {
            u16(ALG_ECC); u16(ALG_SHA256)
            u32(FIXED_TPM or FIXED_PARENT or SENSITIVE_DATA_ORIGIN or USER_WITH_AUTH or NO_DA or SIGN)
            u16(0) // authPolicy
            u16(ALG_NULL) // symmetric
            u16(ALG_ECDSA); u16(ALG_SHA256) // scheme
            u16(ECC_NIST_P256)
            u16(ALG_NULL) // kdf
            u16(0); u16(0) // unique
        }.toByteArray()
    }
}

/** A TPM 2.0 command buffer: header, handles, optional password session, parameters. */
internal class Tpm2Command(private val code: Int, val name: String, private val sessions: Boolean) {
    private val handles = Writer()
    private var authArea: ByteArray? = null
    private val params = Writer()

    fun handle(h: Int) = handles.u32(h)

    /** TPM_RS_PW with an empty password. */
    fun emptyPasswordSession() {
        authArea = Writer().apply { u32(RS_PW); u16(0); u8(0); u16(0) }.toByteArray()
    }

    /** An empty TPM2B_SENSITIVE_CREATE: no auth value, no data. */
    fun sensitiveCreate() = params.apply { u16(4); u16(0); u16(0) }

    fun sized(v: ByteArray) = params.sized(v)
    fun u16(v: Int) = params.u16(v)
    fun u32(v: Int) = params.u32(v)

    fun encode(): ByteArray {
        val body = Writer().apply {
            bytes(handles.toByteArray())
            authArea?.let { u32(it.size); bytes(it) }
            bytes(params.toByteArray())
        }.toByteArray()
        return Writer().apply {
            u16(if (sessions) ST_SESSIONS else ST_NO_SESSIONS)
            u32(HEADER_SIZE + body.size)
            u32(code)
            bytes(body)
        }.toByteArray()
    }

    /** Big-endian writer for TPM structures. */
    class Writer {
        private val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v) }
        fun u16(v: Int) = apply { out.write(v ushr 8); out.write(v) }
        fun u32(v: Int) = apply { u16(v ushr 16); u16(v) }
        fun bytes(v: ByteArray) = apply { out.write(v) }
        fun sized(v: ByteArray) = apply { u16(v.size); bytes(v) }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private companion object {
        const val ST_NO_SESSIONS = 0x8001
        const val ST_SESSIONS = 0x8002
        const val RS_PW = 0x40000009
    }
}

private const val HEADER_SIZE = 10
private const val RC_FMT1 = 0x80
private const val ALG_NULL = 0x0010
private const val ALG_SHA256 = 0x000B
private const val ALG_ECDSA = 0x0018

private fun ByteBuffer.sized(): ByteArray = ByteArray(short.toInt() and 0xFFFF).also { get(it) }
private fun ByteBuffer.skipSized() {
    val size = short.toInt() and 0xFFFF
    position(position() + size)
}
