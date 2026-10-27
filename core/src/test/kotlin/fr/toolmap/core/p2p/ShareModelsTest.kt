package fr.toolmap.core.p2p

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareModelsTest {
    @Test
    fun `settings and group survive the JSON form`() {
        val group = GroupRecord.create()
        val state = ShareState(ShareSettings("Jean", 3, 60, 25, stun = false), group)
        val back = ShareState.fromJson(state.toJson())
        assertEquals(state.settings, back.settings)
        val restored = back.group!!
        assertContentEquals(group.groupId, restored.groupId)
        assertContentEquals(group.key, restored.key)
        assertEquals(group.selfId, restored.selfId)
    }

    @Test
    fun `icon, wheel colour and trail choice survive, and a document of 0_4_0 keeps its palette colour`() {
        val chosen = ShareSettings(name = "Jo", icon = 4, color = 0xFF123456.toInt(), showTrails = false)
        val back = ShareState.fromJson(ShareState(chosen).toJson()).settings
        assertEquals(chosen, back)
        assertEquals(MemberLook.pack(4, 0xFF123456.toInt()), back.look())

        val old = ShareState.fromJson("""{"settings":{"name":"Jo","colorIndex":2}}""").settings
        assertEquals(MemberLook.INITIALS, old.icon)
        assertTrue(old.showTrails)
        assertEquals(MemberPalette.argb(2), old.colorArgb())

        val withPicture = ShareSettings(icon = 3).withPicture(byteArrayOf(9, 8, 7))
        val kept = ShareState.fromJson(ShareState(withPicture).toJson()).settings
        assertEquals(MemberLook.PICTURE, kept.icon)
        assertContentEquals(byteArrayOf(9, 8, 7), kept.pictureBytes())
        assertEquals(MemberLook.INITIALS, kept.withPicture(null).icon)
        assertNull(kept.withPicture(null).picture)
        // A picture chosen but missing or too large falls back to the initials.
        val big = java.util.Base64.getEncoder().encodeToString(ByteArray(MemberLook.MAX_PICTURE_BYTES + 1))
        val tooBig = ShareState.fromJson("""{"settings":{"icon":127,"picture":"$big"}}""").settings
        assertEquals(MemberLook.INITIALS, tooBig.icon)
        assertNull(tooBig.picture)

        val hostile = ShareState.fromJson("""{"settings":{"icon":99,"color":0}}""").settings
        assertEquals(MemberLook.INITIALS, hostile.icon)
        assertEquals(0xFF000000.toInt(), hostile.colorArgb())
    }

    @Test
    fun `a negative member id and the largest ids keep their value`() {
        for (id in listOf(-1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x7FFF_FFFF_FFFF_FFFFL)) {
            val group = GroupRecord(ByteArray(16), ByteArray(32), id)
            assertEquals(id, ShareState.fromJson(ShareState(group = group).toJson()).group!!.selfId)
        }
    }

    @Test
    fun `without a group, and with damaged or hostile documents, the defaults come back`() {
        assertNull(ShareState.fromJson(ShareState().toJson()).group)
        for (bad in listOf("", "{", "[]", "null", "{\"group\":{\"id\":\"zz\",\"key\":\"00\",\"self\":\"1\"}}")) {
            val s = ShareState.fromJson(bad)
            assertNull(s.group, bad)
            assertEquals(ShareSettings(), s.settings, bad)
        }
        // A key of the wrong size is not accepted.
        val shortKey = "{\"group\":{\"id\":\"${"00".repeat(16)}\",\"key\":\"${"00".repeat(5)}\",\"self\":\"1\"}}"
        assertNull(ShareState.fromJson(shortKey).group)
    }

    @Test
    fun `values out of range are brought back into range`() {
        val s =
            ShareState.fromJson(
                "{\"settings\":{\"name\":\"${"x".repeat(
                    60,
                )}\",\"colorIndex\":-1,\"intervalSec\":1,\"minMoveMeters\":-5}}",
            ).settings
        assertTrue(s.name.length <= 24)
        assertEquals(MemberPalette.colors.size - 1, s.colorIndex)
        assertEquals(ShareCadence.MIN_INTERVAL_S, s.intervalSec)
        assertEquals(0, s.minMoveMeters)
        assertEquals(1800, ShareState.fromJson("{\"settings\":{\"intervalSec\":99999}}").settings.intervalSec)
        // Unknown fields from a later version are ignored.
        assertNotNull(ShareState.fromJson("{\"future\":1,\"settings\":{\"name\":\"a\"}}"))
    }

    @Test
    fun `each group gets its own id, key and member id`() {
        val a = GroupRecord.create()
        val b = GroupRecord.create()
        assertNotEquals(a.selfId, b.selfId)
        assertTrue(!a.groupId.contentEquals(b.groupId))
        assertTrue(!a.key.contentEquals(b.key))
        assertNotEquals(0L, a.selfId)
        assertEquals(30, ShareSettings().cadence().intervalSec)
    }
}

class ShareGeoJsonTest {
    private val now = 1_790_000_000_000L

    private fun fix(
        lat: Double,
        lon: Double,
        ageSec: Long = 0,
    ) = PeerFix(lat, lon, timeSec = now / 1000 - ageSec)

    private fun member(
        id: Long,
        name: String,
        status: MemberStatus = MemberStatus.DIRECT,
        self: Boolean = false,
        fix: PeerFix? = null,
        trail: List<PeerFix> = emptyList(),
        color: Int = 0xFFD32F2F.toInt(),
    ) = MemberView(id, name, color, status, self, now, fix, trail)

    private fun parse(json: String) = kotlinx.serialization.json.Json.parseToJsonElement(json)

    @Test
    fun `members with a position become points, this device and the unlocated are left out`() {
        val json =
            ShareGeoJson.members(
                listOf(
                    member(1, "Moi", self = true, fix = fix(45.0, 6.0)),
                    member(2, "Alice", fix = fix(45.1234567, 6.7654321)),
                    member(3, "Sans position"),
                ),
                emptySet(),
                now,
            )
        val features = parse(json).jsonObject["features"]!!.jsonArray
        assertEquals(1, features.size)
        val f = features.single().jsonObject
        val props = f["properties"]!!.jsonObject
        assertEquals("2", props["mid"]!!.jsonPrimitive.content)
        assertEquals("Alice", props["name"]!!.jsonPrimitive.content)
        assertEquals("#D32F2F", props["color"]!!.jsonPrimitive.content)
        assertFalse(props["old"]!!.jsonPrimitive.boolean)
        assertFalse(props["sos"]!!.jsonPrimitive.boolean)
        val c = f["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(6.7654321, c[0].jsonPrimitive.double, 1e-9)
        assertEquals(45.1234567, c[1].jsonPrimitive.double, 1e-9)
    }

    @Test
    fun `a 64-bit id survives as text, a name with quotes stays valid JSON`() {
        val id = Long.MAX_VALUE - 5
        val json = ShareGeoJson.members(listOf(member(id, "Jo \"le Fou\"", fix = fix(1.0, 2.0))), setOf(id), now)
        val props = parse(json).jsonObject["features"]!!.jsonArray.single().jsonObject["properties"]!!.jsonObject
        assertEquals(id.toString(), props["mid"]!!.jsonPrimitive.content)
        assertEquals("Jo \"le Fou\"", props["name"]!!.jsonPrimitive.content)
        assertTrue(props["sos"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `each member names its marker image`() {
        val look = MemberLook.pack(MemberLook.ICONS.indexOf("fox_head"), 0xFFAA0000.toInt())
        val json = ShareGeoJson.members(listOf(member(1, "Jo", color = look, fix = fix(1.0, 2.0))), emptySet(), now)
        val props = parse(json).jsonObject["features"]!!.jsonArray.single().jsonObject["properties"]!!.jsonObject
        assertEquals("tm-m-aa0000-fox_head", props["img"]!!.jsonPrimitive.content)
        assertEquals("#AA0000", props["color"]!!.jsonPrimitive.content)
    }

    @Test
    fun `lost members and old positions are faded`() {
        val json =
            ShareGeoJson.members(
                listOf(
                    member(1, "Perdu", status = MemberStatus.LOST, fix = fix(1.0, 1.0)),
                    member(2, "Vieux", fix = fix(2.0, 2.0, ageSec = 11 * 60)),
                    member(3, "Frais", fix = fix(3.0, 3.0, ageSec = 9 * 60)),
                ),
                emptySet(),
                now,
            )
        val old =
            parse(json).jsonObject["features"]!!.jsonArray.map {
                it.jsonObject["properties"]!!.jsonObject["old"]!!.jsonPrimitive.boolean
            }
        assertEquals(listOf(true, true, false), old)
    }

    @Test
    fun `a trail needs two points and keeps their order`() {
        val json =
            ShareGeoJson.trails(
                listOf(
                    member(1, "Une seule", trail = listOf(fix(1.0, 1.0))),
                    member(
                        2,
                        "Alice",
                        trail = listOf(fix(45.0, 6.0), fix(45.1, 6.1), fix(45.2, 6.2)),
                        color = 0xFF1976D2.toInt(),
                    ),
                    member(3, "Moi", self = true, trail = listOf(fix(1.0, 1.0), fix(2.0, 2.0))),
                ),
            )
        val features = parse(json).jsonObject["features"]!!.jsonArray
        assertEquals(1, features.size)
        val f = features.single().jsonObject
        assertEquals("#1976D2", f["properties"]!!.jsonObject["color"]!!.jsonPrimitive.content)
        val coords = f["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(3, coords.size)
        assertEquals(45.2, coords[2].jsonArray[1].jsonPrimitive.double, 1e-9)
    }

    @Test
    fun `no member gives an empty collection`() {
        assertEquals(
            """{"type":"FeatureCollection","features":[]}""",
            ShareGeoJson.members(emptyList(), emptySet(), now),
        )
        assertEquals("""{"type":"FeatureCollection","features":[]}""", ShareGeoJson.trails(emptyList()))
    }
}
