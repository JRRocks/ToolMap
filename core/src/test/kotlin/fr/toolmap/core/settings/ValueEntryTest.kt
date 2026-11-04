package fr.toolmap.core.settings

import fr.toolmap.core.settings.ValueEntry.Result
import fr.toolmap.core.settings.ValueEntry.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class ValueEntryTest {
    @Test
    fun `a typed whole number is read and checked against its limits`() {
        assertEquals(Result.Value(6200), ValueEntry.parse("6200", -500, 9000))
        assertEquals(Result.Value(-300), ValueEntry.parse("-300", -500, 9000))
        assertEquals(Result.Value(-300), ValueEntry.parse("−300", -500, 9000))
        assertEquals(Result.Value(10_000), ValueEntry.parse("10 000", 0, 10_000))
        assertEquals(Result.Value(7), ValueEntry.parse(" 7 ", 0, 10_000))
        assertEquals(Result.Empty, ValueEntry.parse("  ", 0, 24))
        assertEquals(Result.OutOfRange(0, 24), ValueEntry.parse("25", 0, 24))
        assertEquals(Result.OutOfRange(-500, 9000), ValueEntry.parse("-501", -500, 9000))
        assertEquals(Result.OutOfRange(0, 24), ValueEntry.parse("99999999999999", 0, 24))
        assertEquals(Result.Invalid, ValueEntry.parse("1,5", 0, 24))
        assertEquals(Result.Invalid, ValueEntry.parse("abc", 0, 24))
    }

    @Test
    fun `a duration is typed in seconds, minutes or hours`() {
        assertEquals(Result.Value(45), ValueEntry.parseSeconds("45", TimeUnit.SECONDS, 1, 86_400))
        assertEquals(Result.Value(180), ValueEntry.parseSeconds("3", TimeUnit.MINUTES, 1, 86_400))
        assertEquals(Result.Value(86_400), ValueEntry.parseSeconds("24", TimeUnit.HOURS, 1, 86_400))
        assertEquals(Result.OutOfRange(1, 86_400), ValueEntry.parseSeconds("25", TimeUnit.HOURS, 1, 86_400))
        assertEquals(Result.OutOfRange(1, 86_400), ValueEntry.parseSeconds("0", TimeUnit.SECONDS, 1, 86_400))
        assertEquals(Result.Empty, ValueEntry.parseSeconds("", TimeUnit.MINUTES, 1, 86_400))
    }

    @Test
    fun `a duration is shown in the largest unit that keeps it whole`() {
        assertEquals(TimeUnit.HOURS, ValueEntry.unitOf(7200))
        assertEquals(TimeUnit.MINUTES, ValueEntry.unitOf(120))
        assertEquals(TimeUnit.SECONDS, ValueEntry.unitOf(90))
        assertEquals(TimeUnit.SECONDS, ValueEntry.unitOf(45))
        assertEquals(TimeUnit.SECONDS, ValueEntry.unitOf(0))
    }
}
