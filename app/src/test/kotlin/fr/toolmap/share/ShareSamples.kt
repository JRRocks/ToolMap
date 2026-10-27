package fr.toolmap.share

import fr.toolmap.core.p2p.MemberLook
import fr.toolmap.core.p2p.MemberStatus
import fr.toolmap.core.p2p.MemberView
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.ServerState
import fr.toolmap.core.p2p.ShareSettings

/**
 * The group of the preview of P29.8 (`docs/previews/design-08-partage.html`): « Pixel 7 » (you), « Galaxy A54 » online
 * with a fix of 20 s ago, « Galaxy S23 » out of sync for 4 minutes, « Pixel 6a » offline for 14 minutes, and one
 * message; for the tests and renders of the sharing screens.
 */
object ShareSamples {
    private const val SEC = 1_000L
    private const val MIN = 60_000L

    private fun look(
        icon: String,
        argb: Long,
    ) = MemberLook.pack(MemberLook.ICONS.indexOf(icon), argb.toInt())

    private fun fix(
        nowMs: Long,
        agoMs: Long,
        lat: Double,
        lon: Double,
    ) = PeerFix(
        lat,
        lon,
        altM = 274,
        accuracyM = 6,
        speedMs = 1.17,
        timeSec = (nowMs - agoMs) / SEC,
        battery = 64,
    )

    val settings =
        ShareSettings(name = "Pixel 7", icon = MemberLook.ICONS.indexOf("fox_head"), color = 0xFF1E88E5.toInt())

    fun a54(nowMs: Long) =
        MemberView(
            id = 2L,
            name = "Galaxy A54",
            color = look("octopus", 0xFFEF6C00),
            status = MemberStatus.DIRECT,
            isSelf = false,
            lastHeardMs = nowMs - 20 * SEC,
            fix = fix(nowMs, 20 * SEC, 45.4700, 5.9800),
            trail = listOf(fix(nowMs, 60 * SEC, 45.4690, 5.9790), fix(nowMs, 20 * SEC, 45.4700, 5.9800)),
        )

    fun ui(
        nowMs: Long,
        inGroup: Boolean,
    ): ShareUi {
        if (!inGroup) return ShareUi(settings = settings, myName = "Pixel 7")
        val self =
            MemberView(
                1L,
                "Pixel 7",
                settings.look(),
                MemberStatus.DIRECT,
                true,
                nowMs,
                fix(nowMs, 5 * SEC, 45.46, 5.97),
                emptyList(),
            )
        val s23 =
            MemberView(
                3L,
                "Galaxy S23",
                look("robot_golem", 0xFF8E24AA),
                MemberStatus.LOST,
                false,
                nowMs - 4 * MIN,
                fix(nowMs, 4 * MIN, 45.48, 5.95),
                emptyList(),
            )
        val p6a =
            MemberView(
                4L,
                "Pixel 6a",
                look("unicorn", 0xFF43A047),
                MemberStatus.LOST,
                false,
                nowMs - 14 * MIN,
                fix(nowMs, 14 * MIN, 45.47, 6.00),
                emptyList(),
            )
        return ShareUi(
            settings = settings,
            inGroup = true,
            sharing = true,
            selfId = 1L,
            members = listOf(self, a54(nowMs), s23, p6a),
            messages =
                listOf(ShareMessage(2L, "Galaxy A54", "On attend au Pont du Furet", nowMs - 2 * MIN, mine = false)),
            onServer = true,
            server = ServerState.ONLINE,
            alerts = listOf(ShareAlert.POWER_SAVER),
            myName = "Pixel 7",
            unread = 1,
        )
    }
}
