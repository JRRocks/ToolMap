package fr.toolmap.operation.demo

import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import fr.toolmap.data.hierarchy.OperationPhase
import fr.toolmap.operation.OperationFacts
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The demonstration of the « Opération » mode (P39.8) built as the app builds it: 22 controllers, each with its storage
 * and a key store in memory, in a room in memory. It must give the facts of the maquettes, from every place.
 */
class DemoWorldTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val problems = ArrayList<Problem>()
    private val previousSink = Problems.sink

    @BeforeTest
    fun catchProblems() {
        Problems.sink = Problems.Sink { synchronized(problems) { problems.add(it) } }
    }

    @AfterTest
    fun restoreSink() {
        Problems.sink = previousSink
        synchronized(problems) { assertTrue(problems.none { !it.expected }, "bugs reported: $problems") }
    }

    @Test
    fun `the demonstration plays the facts of the maquettes`() =
        runBlocking {
            val world = assertNotNull(DemoWorld.build(temp.newFolder("demo")) { step -> println("demo $step") })
            try {
                val creator = OperationFacts(world.seats.getValue(1).view.value)
                assertEquals(OperationPhase.PLACED, creator.view.phase)
                assertEquals("Exercice Vallon", creator.operationName)
                assertEquals(listOf("Nord", "Sud", "Est", "Ouest"), creator.groupOrder.map { creator.groupName(it) })
                assertEquals(22, creator.total)
                assertEquals(20, creator.placedCount)
                assertEquals(listOf(21, 22), creator.waitingMembers.map { world.numberOf(it) })
                assertEquals(listOf(1, 5, 9), creator.adminList.map { world.numberOf(it) })
                val nord = creator.groupOrder[0]
                assertEquals(listOf(4, 5, 2, 7, 8), creator.membersOf(nord).map { world.numberOf(it) })
                assertNull(creator.leaderOf(creator.groupOrder[3]), "Ouest has no chief")
                assertEquals(2, world.numberOf(requireNotNull(creator.state?.leader)))
                assertEquals(3, world.numberOf(requireNotNull(creator.state?.deputy)))
                // Membres 8, 12 and 20 are off; the others are in the room.
                assertTrue(DemoWorld.OFF.none { creator.isOnline(world.ids.getValue(it)) })
                assertTrue(creator.isOnline(world.ids.getValue(4)))
                // A member placed reads the names of the placed members, not those of who waits (Q10, Q11); an
                // administrator reads those too; who waits reads none but its own and the operation's (Q2, T14).
                val member = world.seats.getValue(7).view.value
                assertEquals(OperationPhase.PLACED, member.phase)
                assertTrue(
                    member.members.filter { !it.waiting }.all { it.name != null } &&
                        member.groups.all { it.name != null },
                )
                assertTrue(member.members.filter { it.waiting }.all { it.name == null })
                assertTrue(
                    world.seats.getValue(5).view.value.members.all {
                        it.name != null
                    },
                    "an administrator reads them all",
                )
                val waiting = world.seats.getValue(21).view.value
                assertEquals(OperationPhase.WAITING, waiting.phase)
                assertEquals("Exercice Vallon", waiting.operationName)
                assertEquals(listOf("Membre 21"), waiting.members.mapNotNull { it.name })
                assertTrue(waiting.groups.all { it.name == null })
            } finally {
                world.close()
            }
        }
}
