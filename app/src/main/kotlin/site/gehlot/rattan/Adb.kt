package site.gehlot.rattan

import android.content.Context
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * A minimal client for the TV's own ADB daemon on 127.0.0.1:5555 (network debugging). It runs one
 * shell command, which lets the clean-up button force-stop apps the way `adb shell` can. The first
 * time, the TV asks "Allow network debugging?" for Rattan's key; with "Always allow" it never asks
 * again. Speaks the plain ADB protocol: CNXN, RSA AUTH, then one OPEN "shell:" stream.
 */
object Adb {
    private const val PORT = 5555
    private const val CNXN = 0x4e584e43
    private const val AUTH = 0x48545541
    private const val OPEN = 0x4e45504f
    private const val OKAY = 0x59414b4f
    private const val CLSE = 0x45534c43
    private const val WRTE = 0x45545257
    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_PUBLIC_KEY = 3
    private const val VERSION = 0x01000001
    private const val MAX_DATA = 256 * 1024

    /** ASN.1 DigestInfo prefix for SHA-1: adbd's token stands in for the digest. */
    private val SHA1_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    /**
     * Runs [command] in the TV's shell and returns its output, or null when ADB is off, the key was
     * refused or anything else went wrong. Blocks (up to [confirmMs] while the TV shows the prompt),
     * so call it off the main thread.
     */
    fun shell(context: Context, command: String, confirmMs: Int = 60_000): String? = try {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", PORT), 1_000)
            socket.soTimeout = 10_000
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = socket.getOutputStream()
            val keys = keyPair(context)

            send(output, CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
            var sentSignature = false
            while (true) {
                val message = read(input)
                when {
                    message.command == CNXN -> break
                    message.command == AUTH && message.arg0 == AUTH_TOKEN && !sentSignature -> {
                        send(output, AUTH, AUTH_SIGNATURE, 0, sign(keys, message.data))
                        sentSignature = true
                    }
                    message.command == AUTH && message.arg0 == AUTH_TOKEN -> {
                        // Not trusted yet: offer the key; the TV asks the user, so wait longer.
                        send(output, AUTH, AUTH_PUBLIC_KEY, 0, publicKey(keys.public as RSAPublicKey))
                        socket.soTimeout = confirmMs
                    }
                    else -> return null
                }
            }
            socket.soTimeout = 10_000

            val local = 1
            send(output, OPEN, local, 0, "shell:$command\u0000".toByteArray())
            val result = StringBuilder()
            var remote = 0
            var open = true
            while (open) {
                val message = read(input)
                when (message.command) {
                    OKAY -> remote = message.arg0
                    WRTE -> {
                        result.append(String(message.data))
                        send(output, OKAY, local, message.arg0, ByteArray(0))
                    }
                    CLSE -> {
                        send(output, CLSE, local, if (remote != 0) remote else message.arg0, ByteArray(0))
                        open = false
                    }
                }
            }
            result.toString()
        }
    } catch (_: Exception) {
        null
    }

    private fun send(output: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
            .putInt(data.sumOf { it.toInt() and 0xff }).putInt(command.inv())
        output.write(header.array())
        output.write(data)
        output.flush()
    }

    private fun read(input: DataInputStream): Message {
        val header = ByteArray(24).also(input::readFully)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val length = buffer.int
        require(length in 0..MAX_DATA)
        return Message(command, arg0, arg1, ByteArray(length).also(input::readFully))
    }

    private fun sign(keys: KeyPair, token: ByteArray): ByteArray =
        Signature.getInstance("NONEwithRSA").run {
            initSign(keys.private)
            update(SHA1_PREFIX)
            update(token)
            sign()
        }

    /** The key in adbd's own format: Android's RSAPublicKey struct, base64, then a name. */
    private fun publicKey(key: RSAPublicKey): ByteArray {
        val words = 2048 / 32
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n = key.modulus
        val rr = BigInteger.ONE.shiftLeft(2048).pow(2).mod(n)
        val buffer = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(words)
        buffer.putInt(r32.subtract(n.mod(r32).modInverse(r32)).toInt())
        fun putWords(value: BigInteger) {
            var rest = value
            repeat(words) {
                buffer.putInt(rest.mod(r32).toInt())
                rest = rest.shiftRight(32)
            }
        }
        putWords(n)
        putWords(rr)
        buffer.putInt(key.publicExponent.toInt())
        return (Base64.getEncoder().encodeToString(buffer.array()) + " rattan@tv\u0000").toByteArray()
    }

    /** Rattan's own ADB key, made once and kept in app-private storage. */
    @Synchronized
    private fun keyPair(context: Context): KeyPair {
        val privateFile = File(context.noBackupFilesDir, "adbkey")
        val publicFile = File(context.noBackupFilesDir, "adbkey.pub")
        val factory = KeyFactory.getInstance("RSA")
        if (privateFile.exists() && publicFile.exists()) {
            return KeyPair(
                factory.generatePublic(X509EncodedKeySpec(publicFile.readBytes())),
                factory.generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes())),
            )
        }
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        publicFile.writeBytes(keys.public.encoded)
        privateFile.writeBytes(keys.private.encoded)
        return keys
    }
}
