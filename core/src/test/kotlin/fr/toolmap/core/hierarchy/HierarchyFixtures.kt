package fr.toolmap.core.hierarchy

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Identifiers of the tests: A < B < C < D in the order of the operations at equal clocks. */
internal const val A = 0x10L
internal const val B = 0x20L
internal const val C = 0x30L
internal const val D = 0x40L
internal const val OUTSIDER = 0x99L
internal const val G1 = 0x101L
internal const val G2 = 0x102L
internal const val NONCE = 7L

internal fun newEnsemble(rules: HierarchyRules = HierarchyRules.DEFAULT): HierarchyReplica =
    HierarchyReplica.create(A, "Alpha", "Sortie du jour", NONCE, rules)

/** Asks for [change] and expects it to be written. */
internal fun HierarchyReplica.must(change: Change): Op = assertIs<IssueResult.Issued>(issue(change), "$change").op

/** Asks for [change] and expects this phone to refuse it. */
internal fun HierarchyReplica.refused(change: Change): Rejection =
    assertIs<IssueResult.Refused>(issue(change), "$change").reason

/** The copy of [self], a phone that received everything [this] holds. */
internal fun HierarchyReplica.copyFor(self: Long): HierarchyReplica = HierarchyReplica.restore(ensembleId, self, ops())

/** Why [op] changed nothing on this phone; `null` when it applied. */
internal fun HierarchyReplica.rejectionOf(op: Op): Rejection? = history().single { it.op == op }.rejection

/** One catch-up between two phones, through the bytes of the format, until neither has anything to send. */
internal fun sync(
    a: HierarchyReplica,
    b: HierarchyReplica,
    max: Int = HierarchyWire.MAX_BATCH,
): Int {
    var rounds = 0
    while (true) {
        val toB = a.missingFor(HierarchyWire.decodeSummary(HierarchyWire.encodeSummary(b.summary())), max)
        val toA = b.missingFor(HierarchyWire.decodeSummary(HierarchyWire.encodeSummary(a.summary())), max)
        if (toA.isEmpty() && toB.isEmpty()) return rounds
        for (block in HierarchyWire.encodeOpBlocks(toB)) b.addAll(HierarchyWire.decodeOps(block))
        for (block in HierarchyWire.encodeOpBlocks(toA)) a.addAll(HierarchyWire.decodeOps(block))
        rounds++
        check(rounds < 1000) { "the catch-up does not end" }
    }
}

/** Same state, same refusals, same fingerprint, and a state with nothing wrong. */
internal fun assertSame(
    expected: HierarchyReplica,
    actual: HierarchyReplica,
    message: String = "",
) {
    assertEquals(expected.state, actual.state, message)
    assertEquals(expected.summary(), actual.summary(), message)
    assertEquals(
        expected.history().map { it.op to it.rejection },
        actual.history().map { it.op to it.rejection },
        message,
    )
    assertNull(actual.state.inconsistency(), message)
}

/** An ensemble with A (creator, administrator), B (administrator), C and D, and the groups G1 and G2. */
internal fun staffed(rules: HierarchyRules = HierarchyRules.DEFAULT): HierarchyReplica =
    newEnsemble(rules).apply {
        must(Change.AdmitMember(B, "Bravo"))
        must(Change.AdmitMember(C, "Charlie"))
        must(Change.AdmitMember(D, "Delta"))
        must(Change.AddAdmin(B))
        must(Change.CreateGroup(G1, "Groupe 1"))
        must(Change.CreateGroup(G2, "Groupe 2"))
    }
