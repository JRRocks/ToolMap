package fr.toolmap.core.hierarchy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What signing every position costs (the user's answer S5, `docs/specs/group-hierarchy.md` § 8.5): sizes, checked;
 * times, measured on the JVM that runs the tests and written to `build/hierarchy-position-cost.txt`. Nothing here is
 * measured on a phone: a phone is slower, and a signing key kept in its Keystore slower still.
 */
class PositionCostTest {
    /** A position as the mesh carries one today: about thirty bytes (coordinates, altitude, speed, time). */
    private val position = ByteArray(32) { it.toByte() }
    private val unsigned = HierarchyRules(signedShares = ShareKind.entries.toSet() - ShareKind.POSITION)

    private fun microseconds(
        times: Int,
        block: (Int) -> Unit,
    ): Double {
        repeat(times / 4) { block(it) }
        val start = System.nanoTime()
        repeat(times) { block(it) }
        return (System.nanoTime() - start) / 1000.0 / times
    }

    @Test
    fun `S5 a signed position weighs 64 bytes more, and is refused when its signature is not its author's`() {
        val signed = world()
        val light = world(unsigned)
        val heavy = signed[C].frame(ShareKind.POSITION, Channel.ENSEMBLE, position)
        val bare = light[C].frame(ShareKind.POSITION, Channel.ENSEMBLE, position)
        // Clear header (36), nonce (12), position, signature (64), authentication tag (16).
        assertEquals(36 + 12 + position.size + 16, bare.size)
        assertEquals(bare.size + P256.SIGNATURE_BYTES, heavy.size)
        assertEquals(96, bare.size)
        assertEquals(160, heavy.size)
        assertIs<OpenResult.Opened>(signed[E].open(heavy))
        // The same bytes with one bit of the sealed part changed are not a position of C.
        for (at in listOf(40, 60, heavy.size - 20, heavy.size - 1)) {
            val damaged = heavy.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            assertIs<OpenResult.Refused>(signed[E].open(damaged), "byte $at")
        }
        // A position of the waiting lane is signed like the others.
        val waiting = signed[H].frame(ShareKind.POSITION, Channel.waiting(H), position)
        assertEquals(heavy.size, waiting.size)
    }

    @Test
    fun `S5 the time it takes to sign and to check a position, on this JVM`() {
        val signed = world()
        val light = world(unsigned)
        val rounds = 400
        val keys = keysOf(C)
        val message = ByteArray(96) { it.toByte() }
        val signature = keys.sign(message)
        val sign = microseconds(rounds) { keys.sign(message) }
        val verify = microseconds(rounds) { assertTrue(P256.verify(keys.public.signing, message, signature)) }
        val sealSigned = microseconds(rounds) { signed[C].frame(ShareKind.POSITION, Channel.ENSEMBLE, position) }
        val sealBare = microseconds(rounds) { light[C].frame(ShareKind.POSITION, Channel.ENSEMBLE, position) }
        val heavy = signed[C].frame(ShareKind.POSITION, Channel.ENSEMBLE, position)
        val bare = light[C].frame(ShareKind.POSITION, Channel.ENSEMBLE, position)
        val openSigned = microseconds(rounds) { assertIs<OpenResult.Opened>(signed[E].open(heavy)) }
        val openBare = microseconds(rounds) { assertIs<OpenResult.Opened>(light[E].open(bare)) }
        val report =
            listOf(
                "Position of ${position.size} bytes, P-256, ${System.getProperty("java.vm.name")} " +
                    "${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} processors.",
                "Measured on the JVM of the build, $rounds rounds each after a warm-up. Not measured on a phone.",
                "frame, unsigned position: ${bare.size} bytes",
                "frame, signed position:   ${heavy.size} bytes",
                "ECDSA signature alone:    %.0f µs".format(sign),
                "ECDSA verification alone: %.0f µs".format(verify),
                "seal, unsigned position:  %.0f µs".format(sealBare),
                "seal, signed position:    %.0f µs".format(sealSigned),
                "open, unsigned position:  %.0f µs".format(openBare),
                "open, signed position:    %.0f µs".format(openSigned),
            )
        File("build").mkdirs()
        File("build/hierarchy-position-cost.txt").writeText(report.joinToString("\n", postfix = "\n"))
        println(report.joinToString("\n"))
        // No bound on a time: a slow machine must not fail the build. Only that signing is what costs.
        assertTrue(sign > 0 && verify > 0 && sealSigned > 0 && openSigned > 0 && sealBare > 0 && openBare > 0)
    }
}
