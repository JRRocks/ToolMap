package fr.toolmap.share

import androidx.annotation.DrawableRes
import fr.toolmap.R
import fr.toolmap.core.p2p.MemberLook

/** The drawables of [MemberLook.ICONS], same order; `null` for the initials. `MemberIconsTest` keeps them in step. */
internal val MEMBER_ICON_RES: List<Int?> =
    listOf(
        null,
        R.drawable.ic_token_chess_knight,
        R.drawable.ic_token_wolf_head,
        R.drawable.ic_token_fox_head,
        R.drawable.ic_token_eagle_head,
        R.drawable.ic_token_octopus,
        R.drawable.ic_token_dinosaur_rex,
        R.drawable.ic_token_unicorn,
        R.drawable.ic_token_dragon_head,
        R.drawable.ic_token_ghost,
        R.drawable.ic_token_alien_skull,
        R.drawable.ic_token_robot_golem,
        R.drawable.ic_token_wizard_face,
        R.drawable.ic_token_ninja_head,
        R.drawable.ic_token_viking_head,
    )

/** The silhouette of token [id]; `null` for the initials, the picture or an unknown id. */
@DrawableRes
internal fun memberIconRes(id: Int): Int? = MEMBER_ICON_RES.getOrNull(id)
