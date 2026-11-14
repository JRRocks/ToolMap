package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.WireException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hidden names and the cards that open them (`docs/specs/group-hierarchy.md` § 8.8, the user's answers Q2 and Q11). */
class NamesTest {
    @Test
    fun `a hidden name says nothing of the name, and only its own card opens it`() {
        val one = Names.hide("Charlie")
        val two = Names.hide("Charlie")
        assertTrue(one.valid && two.valid)
        assertEquals(HiddenName.BYTES, one.hidden.commitment.size)
        // The same name hidden twice gives two commitments: nobody tries names against one.
        assertNotEquals(one.hidden, two.hidden)
        assertEquals(one.hidden, NameCard(one.salt, "Charlie").hidden)
        assertNotEquals(one.hidden, NameCard(one.salt, "Charlie ").hidden)
        assertEquals(one, NameCard(one.salt.copyOf(), "Charlie"))
        val book = NameBook()
        assertTrue(book.learn(one))
        assertEquals("Charlie", book.nameOf(one.hidden))
        assertNull(book.nameOf(two.hidden))
        assertNull(book.nameOf(null))
        // A name every phone refuses is no card: blank, a control character, more than 64 bytes, a bad salt.
        for (name in listOf("", " ", "deux\nlignes", "x".repeat(Names.MAX_BYTES + 1), "é".repeat(33))) {
            assertFalse(NameCard(one.salt, name).valid, name)
            assertFalse(book.learn(NameCard(one.salt, name)), name)
        }
        assertTrue(NameCard(one.salt, "é".repeat(32)).valid)
        assertFalse(book.learn(NameCard(ByteArray(8), "Charlie")))
    }

    @Test
    fun `a book keeps the cards of the names of the ensemble and hands them to who arrives`() {
        val state = deployed().state
        val book = NameBook()
        val names =
            listOf("Sortie du jour", "Groupe 1", "Groupe 2", "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot")
        assertEquals(names.size, book.missing(state).size)
        for (name in names.drop(2)) assertTrue(book.learn(card(name)))
        assertEquals(listOf(hidden("Sortie du jour"), hidden("Groupe 1")), book.missing(state))
        assertEquals("Delta", book.nameOf(state, D))
        assertNull(book.nameOf(state, OUTSIDER))
        assertNull(book.nameOf(state.name))
        // A card for a name the ensemble does not carry is kept (it may come before its operation), not handed on.
        assertTrue(book.learn(card("Hotel")))
        assertEquals(names.drop(2).toSet(), book.cardsFor(state).map { it.name }.toSet())
        book.prune(state)
        assertNull(book.nameOf(hidden("Hotel")))
        assertEquals("Echo", book.nameOf(state, E))
        book.clear()
        assertEquals(names.size, book.missing(state).size)
        // It takes names from a share of names only.
        val payload = NameBook.encode(names.map(::card))
        assertEquals(0, book.accept(Share(ShareKind.MESSAGE, Channel.ENSEMBLE, B, payload)))
        assertEquals(names.size, book.accept(Share(ShareKind.ROSTER, Channel.ENSEMBLE, B, payload)))
        assertEquals(0, book.accept(Share(ShareKind.ROSTER, Channel.ENSEMBLE, B, payload)))
        assertEquals(0, book.accept(Share(ShareKind.ROSTER, Channel.ENSEMBLE, B, payload.copyOf(payload.size - 1))))
    }

    @Test
    fun `the cards survive their format, and bytes that are no cards are refused without anything else`() {
        val cards = listOf(card("Alpha"), card("x".repeat(Names.MAX_BYTES)), card("Écho"), Names.hide("Zoulou"))
        val payload = NameBook.encode(cards)
        assertEquals(cards, NameBook.decode(payload))
        assertContentEquals(payload, NameBook.encode(NameBook.decode(payload)))
        assertEquals(2 + 4 * (NameCard.SALT_BYTES + 1) + cards.sumOf { it.name.toByteArray().size }, payload.size)
        assertEquals(emptyList(), NameBook.decode(NameBook.encode(emptyList())))
        // Many cards go in several shares, each small enough for one.
        val many = List(300) { card("Membre $it") }
        val parts = NameBook.encodeAll(many)
        assertEquals(3, parts.size)
        assertTrue(parts.all { it.size <= ShareSeal.MAX_PAYLOAD })
        assertEquals(many, parts.flatMap(NameBook::decode))
        assertFailsWith<IllegalArgumentException> { NameBook.encode(many) }
        // Cut, too long, a name no phone accepts, a count that lies.
        for (length in 0 until payload.size) {
            assertFailsWith<WireException>("cut at $length") { NameBook.decode(payload.copyOf(length)) }
        }
        assertFailsWith<WireException> { NameBook.decode(payload + 0) }
        val blank = byteArrayOf(0, 1) + ByteArray(NameCard.SALT_BYTES) + byteArrayOf(1, ' '.code.toByte())
        assertFailsWith<WireException> { NameBook.decode(blank) }
        assertFailsWith<WireException> { NameBook.decode(byteArrayOf(1, 0)) }
        val random = Random(20261114)
        repeat(2000) {
            val noise = random.nextBytes(random.nextInt(120))
            try {
                NameBook.decode(noise).forEach { assertTrue(it.valid) }
            } catch (_: WireException) {
                // Refused: what is expected of noise.
            }
        }
    }
}
