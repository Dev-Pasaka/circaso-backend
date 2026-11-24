package com.circaso.share.domain.support

/**
 * Keccak-256 as Ethereum uses it (the original padding, not SHA3-256's), in plain Kotlin so the domain stays free of
 * libraries. Only for EIP-55 checksums of pay links' addresses: 40 ASCII characters, one block.
 */
internal object Keccak256 {
    private const val RATE = 136
    private const val ROUNDS = 24
    private const val LANES = 25
    private const val OUT = 32

    private val ROUND_CONSTANTS =
        ulongArrayOf(
            0x0000000000000001uL,
            0x0000000000008082uL,
            0x800000000000808AuL,
            0x8000000080008000uL,
            0x000000000000808BuL,
            0x0000000080000001uL,
            0x8000000080008081uL,
            0x8000000000008009uL,
            0x000000000000008AuL,
            0x0000000000000088uL,
            0x0000000080008009uL,
            0x000000008000000AuL,
            0x000000008000808BuL,
            0x800000000000008BuL,
            0x8000000000008089uL,
            0x8000000000008003uL,
            0x8000000000008002uL,
            0x8000000000000080uL,
            0x000000000000800AuL,
            0x800000008000000AuL,
            0x8000000080008081uL,
            0x8000000000008080uL,
            0x0000000080000001uL,
            0x8000000080008008uL,
        ).map { it.toLong() }.toLongArray()

    /** Rotation offsets, indexed x + 5y. */
    private val ROTATIONS =
        intArrayOf(0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14)

    fun hash(input: ByteArray): ByteArray {
        val state = LongArray(LANES)
        val blocks = input.size / RATE + 1
        val padded = input.copyOf(blocks * RATE)
        padded[input.size] = (padded[input.size].toInt() xor 0x01).toByte()
        padded[padded.size - 1] = (padded[padded.size - 1].toInt() xor 0x80).toByte()
        for (b in 0 until blocks) {
            for (i in 0 until RATE / 8) state[i] = state[i] xor lane(padded, b * RATE + i * 8)
            permute(state)
        }
        return ByteArray(OUT) { i -> (state[i / 8] ushr (8 * (i % 8))).toByte() }
    }

    private fun lane(bytes: ByteArray, at: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (bytes[at + k].toLong() and 0xff)
        return v
    }

    private fun permute(a: LongArray) {
        val c = LongArray(5)
        val b = LongArray(LANES)
        for (round in 0 until ROUNDS) {
            for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            for (x in 0 until 5) {
                val d = c[(x + 4) % 5] xor c[(x + 1) % 5].rotateLeft(1)
                for (y in 0 until 5) a[x + 5 * y] = a[x + 5 * y] xor d
            }
            for (x in 0 until 5) {
                for (y in 0 until 5) {
                    b[y + 5 * ((2 * x + 3 * y) % 5)] = a[x + 5 * y].rotateLeft(ROTATIONS[x + 5 * y])
                }
            }
            for (y in 0 until 5) {
                for (x in 0 until 5) {
                    a[x + 5 * y] = b[x + 5 * y] xor (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
                }
            }
            a[0] = a[0] xor ROUND_CONSTANTS[round]
        }
    }
}
