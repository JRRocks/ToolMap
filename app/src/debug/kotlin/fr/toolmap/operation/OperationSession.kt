package fr.toolmap.operation

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.toolmap.core.eventlog.EventLog
import fr.toolmap.core.hierarchy.Change
import fr.toolmap.core.hierarchy.HierarchyState
import fr.toolmap.core.hierarchy.Names
import fr.toolmap.core.hierarchy.OperationInviteCodec
import fr.toolmap.core.hierarchy.Post
import fr.toolmap.core.hierarchy.Rejection
import fr.toolmap.core.hierarchy.ShareOutcome
import fr.toolmap.core.p2p.PairingCodec
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.problem.Problems
import fr.toolmap.core.record.Fix
import fr.toolmap.data.hierarchy.Ending
import fr.toolmap.data.hierarchy.OperationController
import fr.toolmap.data.hierarchy.OperationPhase
import fr.toolmap.data.hierarchy.OperationStorage
import fr.toolmap.data.hierarchy.OperationView
import fr.toolmap.data.hierarchy.StartResult
import fr.toolmap.operation.demo.DemoWorld
import fr.toolmap.record.LocationSource
import fr.toolmap.record.isGpsRefusal
import fr.toolmap.share.AndroidNetworkWatch
import fr.toolmap.share.NetworkWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What a session keeps beside the view of the engine for the screens of one phone. */
data class Extras(
    /** When each member was last seen present (for « Synchronisation perdue · il y a 4 minutes »). */
    val lastSeen: Map<Long, Long> = emptyMap(),
    /** When each waiting member came (« Arrivé il y a 3 minutes »), as this phone saw it. */
    val arrivals: Map<Long, Long> = emptyMap(),
    /** What happened, not said yet. */
    val notices: List<Notice> = emptyList(),
    /** Back to the wait after being in a group (the screen of the wait says « Vous restez dans l'opération »). */
    val waitingAgain: Boolean = false,
    /** The name this phone asked to come in with (shown while it is not let in yet). */
    val selfName: String? = null,
)

/** The departure of a phone got by another one ([OperationSession.departureReceived], M4 A): its operation, and when. */
data class Departure(
    val operation: String,
    val atMs: Long,
)

/** Why an invitation was not taken. */
enum class JoinProblem { BAD_CODE, EXPIRED, CLOCK_OFF, FAILED }

/**
 * The « Opération » mode of this phone for its screens (P39.8, ADR-130): which phone they show (this one, through the
 * [OperationController] of the app, or a phone of the demonstration, [DemoWorld], « voir comme »), where they are
 * ([nav]), the forms, what happened ([Extras.notices]), and the gestures, each a call of the controller.
 *
 * Its own doings, beside the screens: it opens again at start the operation stored on this phone; it lets in, on the
 * phone of the creator or of an administrator, whoever asks with the invitation (the maquettes draw no gesture for
 * that: « La personne donne son nom, puis arrive « en attente » »); it sends the position of this phone while
 * « Partager ma position » is on, or while it waits (T15); while it is on, [OperationService] keeps the app in the
 * foreground with its line in the notification (P39.14, L4).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
class OperationSession
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val real: OperationController,
        private val storage: OperationStorage,
        private val location: LocationSource,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val network: NetworkWatch = AndroidNetworkWatch(context)

        /** The last position of each phone that found no phone to go to (out of network): it goes once one is linked. */
        private val unsent = ConcurrentHashMap<OperationController, PeerFix>()

        // ---- which phone the screens show ---------------------------------------------------------------------

        private val mutableDemo = MutableStateFlow<DemoWorld?>(null)
        val demo: StateFlow<DemoWorld?> = mutableDemo.asStateFlow()
        private val mutableDemoBusy = MutableStateFlow(false)
        val demoBusy: StateFlow<Boolean> = mutableDemoBusy.asStateFlow()

        /** The demonstration could not be built (its step is in the event log and in logcat). */
        val demoFailed = MutableStateFlow(false)
        private val seat = MutableStateFlow(real)

        /** The controller of the phone the screens show. */
        val controller: OperationController get() = seat.value

        val view: StateFlow<OperationView> =
            seat.flatMapLatest { it.view }.stateIn(scope, SharingStarted.Eagerly, real.view.value)

        /** An operation is open: the screen of the sharing is the operation's. */
        val active: StateFlow<Boolean> =
            view.map { it.phase in IN_OPERATION }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

        private val trackers = HashMap<OperationController, Tracker>()
        val extras: StateFlow<Extras> =
            seat.flatMapLatest { trackerOf(it).extras }.stateIn(scope, SharingStarted.Eagerly, Extras())

        // ---- where the screens are, and their forms --------------------------------------------------------------

        val nav = MutableStateFlow(OperationNav())
        val createForm = MutableStateFlow(CreateForm())
        val join = MutableStateFlow<JoinQuestion?>(null)
        val joinProblem = MutableStateFlow<JoinProblem?>(null)

        /** « Opération complète » for the operation of that name (the 31st who scans, `tour2/04-admin-limites`). */
        val full = MutableStateFlow<String?>(null)

        /** The invitation shown in its sheet: the link and the text of its QR code. */
        val invitation = MutableStateFlow<Pair<String, String>?>(null)

        private val mutableRequests = MutableSharedFlow<OperationRequest>(extraBufferCapacity = REQUESTS)
        val requests: SharedFlow<OperationRequest> = mutableRequests.asSharedFlow()

        /** « Partager ma position » (on by default, as drawn). */
        val sharing = MutableStateFlow(prefs.getBoolean(KEY_SHARING, true))

        /** The operations whose reminder the creator closed with « Compris » (T10 B), by identifier. */
        val reminded = MutableStateFlow(prefs.getStringSet(KEY_REMINDED, emptySet()).orEmpty().toSet())

        /** No network on this phone (none, or Android cuts the app off): « Hors réseau » (P39.14, L4 bis). */
        val offline = MutableStateFlow(false)

        /** The operation stored on this phone was opened again at start (or there was none to open). */
        val restored = MutableStateFlow(false)

        /**
         * « Votre départ de « … » est reçu : tout est effacé de ce téléphone. » (M4 A, image 16): the departure of the
         * phone the screens show was got by another phone; said once, on the screen of the sharing, if it was just now.
         */
        val departureReceived = MutableStateFlow<Departure?>(null)

        /**
         * « Départ non reçu » (M4 A, image 16): no phone got the departure of this phone within a week, everything was
         * wiped all the same; kept on this phone until « OK », the app closed meanwhile.
         */
        val departureUnheard = MutableStateFlow(prefs.getString(KEY_UNHEARD, null))

        /**
         * What the notification says while this phone's position goes to the operation (P39.14, L4 A, image 07; L4
         * bis a, image 09), or while this phone left and waits for another one to get its departure (M4 A, image 14);
         * `null` otherwise. [OperationService] stays in the foreground as long as it is not.
         */
        val notice: StateFlow<OperationNotice?> =
            combine(view, sharing, offline) { v, on, off ->
                val name = v.operationName.orEmpty()
                when {
                    v.phase == OperationPhase.LEAVING -> OperationNotice(name, false, v.leavingUntilMs ?: 0L)
                    v.phase == OperationPhase.PLACED && on -> OperationNotice(name, off)
                    else -> null
                }
            }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

        init {
            // The operation stored on this phone comes back with the app (the demonstration never is stored).
            scope.launch {
                if (real.view.value.phase == OperationPhase.NONE) {
                    val id = withContext(Dispatchers.IO) { real.ids().firstOrNull() }
                    if (id != null) {
                        val result = real.open(id)
                        EventLog.event("operation.open", "result" to result)
                    }
                }
                restored.value = true
            }
            sendPositions()
            watchFull()
            watchNetwork()
            keepInForeground()
            followDepartures()
        }

        // ---- entry -------------------------------------------------------------------------------------------------

        /** A scanned or pasted code, or a link: `true` when it is an invitation to an operation (its question shows). */
        fun onCode(code: String): Boolean {
            val text = code.trim()
            if (OperationInviteCodec.parseOrNull(text) == null) return false
            val preview = real.preview(text)
            when {
                preview == null -> {
                    joinProblem.value = JoinProblem.BAD_CODE
                }

                preview.staleness == PairingCodec.Staleness.EXPIRED -> {
                    joinProblem.value = JoinProblem.EXPIRED
                }

                preview.staleness != null -> {
                    joinProblem.value = JoinProblem.CLOCK_OFF
                }

                else -> {
                    join.value = JoinQuestion(text, preview.operationName)
                }
            }
            EventLog.event("operation.invitation", "chars" to text.length, "stale" to preview?.staleness)
            return true
        }

        /** A link opened from another app. */
        fun onLink(link: String): Boolean {
            if (!onCode(link)) return false
            mutableRequests.tryEmit(OperationRequest.SHARE)
            return true
        }

        fun typeJoinName(name: String) = join.update { it?.copy(name = name) }

        fun cancelJoin() {
            join.value = null
        }

        /** « Rejoindre »: this phone asks to come in as [name]; it waits for an administrator from now on. */
        fun confirmJoin(name: String) {
            val question = join.value ?: return
            if (!Names.valid(name.trim())) return
            join.value = question.copy(busy = true)
            scope.launch {
                leaveDemo()
                val result = real.join(question.invitation, name.trim())
                EventLog.event("operation.join", "result" to result)
                if (result == StartResult.STARTED) trackerOf(real).extras.update { it.copy(selfName = name.trim()) }
                join.value = null
                when (result) {
                    StartResult.STARTED -> {
                        nav.value = OperationNav()
                        mutableRequests.tryEmit(OperationRequest.SHARE)
                    }

                    StartResult.EXPIRED -> {
                        joinProblem.value = JoinProblem.EXPIRED
                    }

                    StartResult.BAD_INVITATION -> {
                        joinProblem.value = JoinProblem.BAD_CODE
                    }

                    else -> {
                        joinProblem.value = JoinProblem.FAILED
                    }
                }
            }
        }

        fun clearJoinProblem() {
            joinProblem.value = null
        }

        fun clearFull() {
            full.value = null
        }

        // ---- creation ----------------------------------------------------------------------------------------------

        fun editCreate(update: (CreateForm) -> CreateForm) = createForm.update(update)

        /** « Créer l'opération et inviter » (`tour4/10-creation` A): the operation, its groups, the creator's place. */
        fun create(
            myName: String,
            onCreated: () -> Unit,
        ) {
            val form = createForm.value
            if (form.busy || !Names.valid(form.name.trim())) return
            createForm.value = form.copy(busy = true)
            scope.launch {
                leaveDemo()
                val c = real
                val result = c.create(form.name.trim(), myName.trim().ifEmpty { DEFAULT_NAME })
                EventLog.event("operation.create", "result" to result, "groups" to form.groups.size)
                if (result != StartResult.STARTED) {
                    createForm.update { it.copy(busy = false) }
                    return@launch
                }
                val made = form.groups.map { name -> c.createGroup(name).first }
                // L1 A (`tour8/img/03`): the colours picked for the groups of departure, the others are those of their place.
                form.chosen.forEachIndexed { i, color ->
                    val group = made.getOrNull(i) ?: return@forEachIndexed
                    if (color == null) return@forEachIndexed
                    trackerOf(c).ownColors[group] = color
                    refusal("color", c.setGroupColor(group, OperationFacts.rgbOf(color)))
                }
                val mine = form.myGroup?.let { made.getOrNull(it) }
                val self = c.view.value.self
                if (mine != null && self != null) refusal("place", c.place(self, mine))
                createForm.value = CreateForm()
                nav.value = OperationNav(sheet = OpSheet.Invite)
                loadInvitation()
                onCreated()
            }
        }

        // ---- the screens ------------------------------------------------------------------------------------------

        fun open(page: OpPage) = nav.update { it.open(page) }

        fun sheet(sheet: OpSheet?) {
            nav.update { it.copy(sheet = sheet, menu = false, nameDraft = null) }
            if (sheet == OpSheet.Invite) loadInvitation()
        }

        fun dialog(dialog: OpDialog?) = nav.update { it.copy(dialog = dialog, menu = false, slide = null) }

        fun menu(open: Boolean) = nav.update { it.copy(menu = open) }

        /** Back inside the screens of the operation; `false` when the screen itself is to close. */
        fun back(): Boolean {
            val next = nav.value.back() ?: return false
            nav.value = next
            return true
        }

        fun consume(notice: Notice) {
            trackerOf(controller).extras.update { it.copy(notices = it.notices - notice) }
        }

        fun dismissReminder() {
            val id = view.value.ensembleId ?: return
            val key = java.lang.Long.toHexString(id)
            reminded.update { it + key }
            if (demo.value == null) prefs.edit().putStringSet(KEY_REMINDED, reminded.value).apply()
        }

        fun setSharing(on: Boolean) {
            sharing.value = on
            prefs.edit().putBoolean(KEY_SHARING, on).apply()
        }

        private fun loadInvitation() {
            invitation.value = null
            val c = controller
            scope.launch {
                val link = c.invitationLink()
                val qr = c.invitationQr()
                invitation.value = if (link != null && qr != null) link to qr else null
            }
        }

        // ---- administration -----------------------------------------------------------------------------------------

        private fun act(
            what: String,
            close: Boolean = true,
            call: suspend OperationController.() -> Rejection?,
        ) {
            val c = controller
            if (close) nav.update { it.copy(sheet = null, dialog = null) }
            scope.launch { refusal(what, c.call()) }
        }

        private fun refusal(
            what: String,
            rejection: Rejection?,
        ) {
            if (rejection != null) EventLog.event("operation.refused", "change" to what, "reason" to rejection)
        }

        fun place(
            member: Long,
            group: Long,
        ) = act("place") { place(member, group) }

        fun addMembers(
            group: Long,
            members: List<Long>,
        ) = act("add") {
            members.firstNotNullOfOrNull { place(it, group) }
        }

        fun setPost(
            unit: Long,
            post: Post,
            member: Long?,
        ) = act("post") { if (member == null) clearPost(unit, post) else setPost(unit, post, member) }

        fun addAdmin(member: Long) = act("addAdmin") { addAdmin(member) }

        fun removeAdmin(member: Long) = act("removeAdmin") { removeAdmin(member) }

        fun removeMember(member: Long) = act("removeMember") { removeMember(member) }

        fun unassign(member: Long) = act("unassign") { unassign(member) }

        /** « Créer le groupe … »; [color] (an index of [OperationFacts.GROUP_COLORS]) when one was picked (L1 A). */
        fun createGroup(
            name: String,
            color: Int? = null,
        ) {
            val c = controller
            act("createGroup") {
                if (!Names.valid(name.trim())) return@act Rejection.MALFORMED
                val (group, rejection) = createGroup(name.trim())
                if (group == null || color == null) return@act rejection
                trackerOf(c).ownColors[group] = color
                setGroupColor(group, OperationFacts.rgbOf(color))
            }
        }

        /** « Modifier le groupe » (L1 A, `tour8/img/02`): its new [name], its new [color], or both. */
        fun editGroup(
            group: Long,
            name: String?,
            color: Int?,
        ) {
            val c = controller
            act("editGroup") {
                if (name != null) {
                    if (!Names.valid(name.trim())) return@act Rejection.MALFORMED
                    renameGroup(group, name.trim())?.let { return@act it }
                }
                if (color == null) return@act null
                trackerOf(c).ownColors[group] = color
                setGroupColor(group, OperationFacts.rgbOf(color))
            }
        }

        fun deleteGroup(group: Long) {
            nav.update { n -> n.copy(pages = n.pages.filter { it != OpPage.GroupPage(group) }) }
            act("deleteGroup") { deleteGroup(group) }
        }

        /** « Transmettre à … » (U8 A): the creator hands its role over and stays, as a member of its group. */
        fun transfer(successor: Long) = act("transfer") { change(Change.TransferCreator(successor)) }

        // ---- names (P39.13) ----------------------------------------------------------------------------------------

        /**
         * A new name for [member] at once (N2 A, N14 A); [own]: the creator or an administrator renames itself, it is
         * not told « Un administrateur vous a renommé ».
         */
        fun renameMember(
            member: Long,
            name: String,
            own: Boolean = false,
        ) {
            val trimmed = name.trim()
            if (own) trackerOf(controller).ownName = trimmed
            act("renameMember") { if (Names.valid(trimmed)) renameMember(member, trimmed) else Rejection.MALFORMED }
        }

        /** « Envoyer la demande » (N3 A, N11 B): an administrator accepts or refuses it. */
        fun requestName(name: String) =
            act("requestName") {
                if (Names.valid(name.trim())) requestName(name.trim()) else Rejection.MALFORMED
            }

        /** « Annuler » under its own line, while nobody answered (N7 A). */
        fun withdrawNameRequest() = act("withdrawName", close = false) { withdrawNameRequest() }

        /** « Accepter », « Refuser » (N6 A): an answer that came second is told « Déjà répondu ». */
        fun answerName(
            member: Long,
            accept: Boolean,
        ) {
            val c = controller
            val tracker = trackerOf(c)
            scope.launch {
                val rejection = c.answerName(member, accept)
                refusal("answerName", rejection)
                if (rejection == null) {
                    tracker.myAnswers.add(member)
                } else {
                    tracker.tellIfAnswered(c.view.value, member)
                }
            }
        }

        /** « Renommer l'opération » (N10 A): the invitations already given stay valid. */
        fun renameOperation(name: String) =
            act("renameOperation") {
                if (Names.valid(name.trim())) renameOperation(name.trim()) else Rejection.MALFORMED
            }

        /** « Appliquer » of « Réglages d'envoi » (P39.15, F2 B modifié): the rhythm of the positions of everyone. */
        fun setPositionInterval(seconds: Int) =
            act("setPositionInterval") { change(Change.SetPositionInterval(seconds)) }

        // ---- leaving -----------------------------------------------------------------------------------------------

        /** « Quitter l'opération »: a member leaves; before it was let in, its request is given up and wiped. */
        fun leave() {
            val c = controller
            nav.value = OperationNav()
            trackerOf(c).byItself = true
            scope.launch {
                if (c.view.value.phase == OperationPhase.ASKING) {
                    abandon(c)
                } else {
                    refusal("leave", c.leave())
                }
            }
        }

        /** « Transmettre à … et quitter » (Q13, T8 A). */
        fun handOverAndLeave(successor: Long) {
            val c = controller
            nav.value = OperationNav()
            trackerOf(c).byItself = true
            scope.launch { refusal("handOver", c.handOverAndLeave(successor)) }
        }

        /** The thumb reached the end of « Glisser pour dissoudre » (Q4, T8 A). */
        fun dissolve() {
            val c = controller
            nav.value = OperationNav()
            trackerOf(c).byItself = true
            scope.launch { refusal("dissolve", c.dissolve()) }
        }

        /** A request that was never let in: this phone closes it and wipes its keys and folder. */
        private suspend fun abandon(c: OperationController) {
            val id = c.view.value.storageId
            c.close()
            if (c === real && id != null) withContext(Dispatchers.IO) { storage.wipe(id) }
        }

        // ---- the demonstration (debug build type) ------------------------------------------------------------------

        /** Builds the demonstration and shows it from the phone of Membre 1, the creator. */
        fun startDemo() {
            if (mutableDemoBusy.value) return
            mutableDemoBusy.value = true
            scope.launch {
                mutableDemo.value?.close()
                mutableDemo.value = null
                val started = System.currentTimeMillis()
                val world =
                    withContext(Dispatchers.Default) {
                        // Each step with its time and the heap in use: what the log of the CI shows of a slow build.
                        DemoWorld.build(File(context.cacheDir, DEMO_FOLDER)) { step ->
                            val heap = Runtime.getRuntime().run { (totalMemory() - freeMemory()) / (1024 * 1024) }
                            log("demo $step · ${(System.currentTimeMillis() - started) / 1000} s · heap $heap MB")
                        }
                    }
                mutableDemoBusy.value = false
                if (world == null) {
                    log("demo failed")
                    demoFailed.value = true
                    return@launch
                }
                // The ages of the maquettes: « Arrivé il y a 3 minutes », « Hors ligne · il y a 14 minutes ».
                val now = System.currentTimeMillis()

                fun ago(minutes: Map<Int, Long>): Map<Long, Long> =
                    minutes.entries.associate { (n, m) -> world.ids.getValue(n) to now - m * MINUTE }
                val seen = ago(DemoWorld.SILENT_MINUTES)
                val came = ago(DemoWorld.ARRIVED_MINUTES)
                for (c in world.seats.values) {
                    trackerOf(c).extras.update { it.copy(lastSeen = it.lastSeen + seen, arrivals = it.arrivals + came) }
                }
                mutableDemo.value = world
                seat.value = world.seats.getValue(1)
                nav.value = OperationNav()
                mutableRequests.tryEmit(OperationRequest.SHARE)
                log("demo ready")
            }
        }

        /** « Voir comme » Membre [number] of the demonstration. */
        fun viewAs(number: Int) {
            val world = mutableDemo.value ?: return
            val c = world.seats[number] ?: return
            seat.value = c
            nav.value = OperationNav()
        }

        fun stopDemo() {
            scope.launch { leaveDemo() }
        }

        private suspend fun leaveDemo() {
            val world = mutableDemo.value ?: return
            mutableDemo.value = null
            seat.value = real
            nav.value = OperationNav()
            for (c in world.seats.values) trackers.remove(c)
            withContext(Dispatchers.Default) { world.close() }
        }

        /** The number of [member] in the demonstration (« Membre 7 » is 7); `null` outside it. */
        fun demoNumber(member: Long): Int? = mutableDemo.value?.numberOf(member)

        /** The screens show this phone again, the demonstration kept (the captures of the entry). */
        fun viewReal() {
            seat.value = real
            nav.value = OperationNav()
        }

        /** The phone of Membre [number] of the demonstration. */
        fun demoSeat(number: Int): OperationController? = mutableDemo.value?.seats?.get(number)

        /** What Membre [number] of the demonstration has not been told yet. */
        fun noticesOf(number: Int): List<Notice> =
            demoSeat(number)?.let { trackerOf(it).extras.value.notices }.orEmpty()

        /** Membres [numbers] of the demonstration are told nothing of what came before (the captures). */
        fun forgetNotices(vararg numbers: Int) {
            for (n in numbers) demoSeat(n)?.let { c -> trackerOf(c).extras.update { it.copy(notices = emptyList()) } }
        }

        /** The question of an invitation and its windows are closed (the captures: each scene starts without them). */
        fun closeWindows() {
            join.value = null
            joinProblem.value = null
            full.value = null
        }

        /** Asks [fr.toolmap.MainActivity] to open a screen. */
        fun request(request: OperationRequest) {
            mutableRequests.tryEmit(request)
        }

        /** Runs [block] on the main thread of the session (the captures of the CI, [OperationScenes]). */
        fun run(block: suspend () -> Unit) {
            scope.launch { block() }
        }

        // ---- what runs by itself -----------------------------------------------------------------------------------

        /**
         * The position of this phone, to the operation, while « Partager ma position » is on or while it waits, at the
         * rhythm of the operation (P39.15: a minute unless its chief changed it); a new rhythm applies at once. Out of
         * network, the last one is kept and goes as soon as a phone is linked again, without waiting for the next
         * (P39.14, L4 bis, image 09: as « Entre amis »).
         */
        private fun sendPositions() {
            val every =
                view
                    .map { it.hierarchy?.positionIntervalS ?: HierarchyState.DEFAULT_POSITION_INTERVAL_S }
                    .distinctUntilChanged()
            val sending =
                combine(seat, view.map { it.phase }.distinctUntilChanged(), sharing, every) { c, phase, on, seconds ->
                    c.takeIf { phase == OperationPhase.WAITING || (phase == OperationPhase.PLACED && on) }
                        ?.let { it to seconds }
                }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)
            scope.launch {
                sending
                    .flatMapLatest { going ->
                        if (going == null) {
                            emptyFlow()
                        } else {
                            val (c, seconds) = going
                            location
                                .fixes(seconds * SECOND_MS, 0f)
                                .map { c to it }
                                .catch { e ->
                                    if (e.isGpsRefusal()) Problems.expected("operation.position.gps", e) else throw e
                                }
                        }
                    }.collect { (c, fix) -> send(c, fix.toPeerFix()) }
            }
            scope.launch {
                sending
                    .flatMapLatest { going ->
                        val c = going?.first ?: return@flatMapLatest emptyFlow()
                        c.view.map { it.online.isNotEmpty() }.distinctUntilChanged().map { linked -> c to linked }
                    }.collect { (c, linked) -> if (linked) unsent.remove(c)?.let { send(c, it) } }
            }
        }

        private suspend fun send(
            c: OperationController,
            fix: PeerFix,
        ) {
            if (c.sendPosition(fix) == ShareOutcome.SKIPPED) unsent[c] = fix else unsent.remove(c)
        }

        /**
         * The phone's network, while an operation is open (P39.14, L4 bis): told to the server connection of the
         * operation of this phone, as the sharing « Entre amis » does (a new network: try at once), and to
         * [offline] for the line « Hors réseau » of the notification.
         */
        private fun watchNetwork() {
            val events =
                object : NetworkWatch.Listener {
                    @Volatile private var lost = false

                    @Volatile private var blocked = false

                    override fun onNetwork(
                        name: String,
                        retry: Boolean,
                    ) {
                        lost = false
                        offline.value = blocked
                        real.onNetwork(name, retry)
                    }

                    override fun onBlocked(blocked: Boolean) {
                        this.blocked = blocked
                        offline.value = lost || blocked
                        real.onBlocked(blocked)
                    }

                    override fun onOffline() {
                        lost = true
                        offline.value = true
                        real.onOffline()
                    }

                    override fun onClockChanged() = real.onClockChanged()
                }
            scope.launch {
                combine(active, real.view.map { it.phase in IN_OPERATION }) { shown, open -> shown || open }
                    .distinctUntilChanged()
                    .collectLatest { open ->
                        if (!open) {
                            offline.value = false
                            return@collectLatest
                        }
                        // The callback says nothing when there is no network at all as it is registered.
                        val connectivity = context.getSystemService(ConnectivityManager::class.java)
                        offline.value = connectivity?.activeNetwork == null
                        val handle = network.start(events)
                        try {
                            awaitCancellation()
                        } finally {
                            handle.close()
                        }
                    }
            }
        }

        /**
         * ToolMap comes on screen (P39.14.8): [OperationService] is asked again while a position is to go. Android
         * refuses it while the app is out of sight (placed in the background, the screen off), and nothing else asks
         * it again: once the app is out of sight again, the position stops going.
         */
        fun onAppShown() {
            if (notice.value != null) OperationService.start(context)
        }

        /** [OperationService] in the foreground as soon as this phone's position goes to the operation (L4). */
        private fun keepInForeground() {
            scope.launch {
                notice.map { it != null }.distinctUntilChanged().collect { on ->
                    if (on) OperationService.start(context)
                }
            }
        }

        /**
         * What became of the departure of this phone (M4 A): got by another phone, or given up after a week. The phone
         * of the app always, its operation remembered on this phone from the moment it left (the app may stop before
         * the end); the phone of the demonstration the screens show too (the captures).
         */
        private fun followDepartures() {
            scope.launch { followDeparture(real, remember = true) }
            scope.launch { seat.collectLatest { c -> if (c !== real) followDeparture(c, remember = false) } }
        }

        private suspend fun followDeparture(
            c: OperationController,
            remember: Boolean,
        ) {
            var leaving = if (remember) prefs.getString(KEY_LEAVING, null) else null
            c.view.collect { v ->
                when (v.phase) {
                    OperationPhase.LEAVING -> {
                        val name = v.operationName.orEmpty()
                        if (name != leaving) {
                            leaving = name
                            if (remember) prefs.edit().putString(KEY_LEAVING, name).apply()
                        }
                    }

                    OperationPhase.ENDED -> {
                        val name = leaving ?: return@collect
                        leaving = null
                        if (remember) prefs.edit().remove(KEY_LEAVING).apply()
                        when (v.ending) {
                            Ending.LEFT -> {
                                departureReceived.value = Departure(name, System.currentTimeMillis())
                            }

                            Ending.LEFT_UNHEARD -> {
                                departureUnheard.value = name
                                if (remember) prefs.edit().putString(KEY_UNHEARD, name).apply()
                            }

                            else -> {
                                Unit
                            }
                        }
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }

        /** « OK » of « Départ non reçu ». */
        fun closeDepartureUnheard() {
            departureUnheard.value = null
            prefs.edit().remove(KEY_UNHEARD).apply()
        }

        /** The 31st who asks to come into a full operation is told so, and its request is given up. */
        private fun watchFull() {
            scope.launch {
                view.collect { v ->
                    val s = v.hierarchy ?: return@collect
                    if (v.phase == OperationPhase.ASKING && s.created && s.members.size >= s.rules.limits.maxMembers) {
                        full.value = v.operationName.orEmpty()
                        abandon(controller)
                    }
                }
            }
        }

        private fun trackerOf(c: OperationController): Tracker =
            trackers.getOrPut(c) {
                Tracker(c).also(Tracker::start)
            }

        /** What one phone keeps beside its view, and what it does by itself (it lets in who asks). */
        private inner class Tracker(
            val controller: OperationController,
        ) {
            val extras = MutableStateFlow(Extras())

            /** This phone asked for what ends it (it left, handed over, dissolved): no notice of it. */
            var byItself = false
            private val admitting = HashSet<Long>()

            /** The name this phone gave itself (creator, administrator): not told back as given by someone else. */
            @Volatile var ownName: String? = null

            /** The colours this phone gave groups (L1 bis a): the message is for the others. */
            val ownColors = java.util.concurrent.ConcurrentHashMap<Long, Int>()

            /**
             * The requests of a new name this phone answered (N6 A): when the answer of another administrator turns out
             * to come first in the common order (it was out of network), this phone is told « Déjà répondu ».
             */
            val myAnswers: MutableSet<Long> =
                java.util.concurrent.ConcurrentHashMap
                    .newKeySet()

            /** The answer to [member]'s request was another administrator's: « Déjà répondu ». */
            fun tellIfAnswered(
                v: OperationView,
                member: Long,
            ): Boolean {
                val answer = v.nameAnswers[member] ?: return false
                if (answer.by == v.self) return false
                val by = v.members.firstOrNull { it.id == answer.by }?.name ?: "…"
                val notice = Notice.AlreadyAnswered(by, answer.name ?: "…", answer.accepted)
                extras.update { it.copy(notices = it.notices + notice) }
                return true
            }

            /** What became of the answers of this phone, once the view settled. */
            private fun followAnswers(v: OperationView) {
                val done =
                    myAnswers.filter { m ->
                        tellIfAnswered(v, m) || v.nameRequests.any { it.member == m } ||
                            v.hierarchy?.members?.containsKey(m) == false
                    }
                myAnswers.removeAll(done.toSet())
            }

            fun start() {
                scope.launch { controller.view.collect(::onView) }
                scope.launch {
                    var settled: OperationView? = null
                    controller.view.debounce(SETTLE_MS).collect { v ->
                        val before = settled
                        settled = v
                        if (before == null) return@collect
                        followAnswers(v)
                        val own = ownName
                        val found =
                            Notices.between(before, v, byItself).filterNot {
                                (it is Notice.RenamedByAdmin && it.name == own) ||
                                    (it is Notice.GroupColorChanged && ownColors.remove(it.groupId, it.color))
                            }
                        if (own != null && v.members.firstOrNull { it.id == v.self }?.name == own) ownName = null
                        if (v.phase == OperationPhase.ENDED || v.phase == OperationPhase.NONE) byItself = false
                        if (found.isEmpty()) return@collect
                        val again = found.any { it is Notice.GroupDeleted || it is Notice.Unassigned }
                        extras.update { it.copy(notices = it.notices + found, waitingAgain = it.waitingAgain || again) }
                    }
                }
            }

            private fun onView(v: OperationView) {
                val now = System.currentTimeMillis()
                val state = v.hierarchy
                extras.update { e ->
                    val seen = e.lastSeen.toMutableMap()
                    for (m in v.members) if (m.online && m.id != v.self) seen[m.id] = now
                    val arrivals = e.arrivals.toMutableMap()
                    for (r in v.joinRequests) arrivals.putIfAbsent(r.member, r.receivedAtMs)
                    if (state != null) for (m in state.waiting()) arrivals.putIfAbsent(m.id, now)
                    e.copy(
                        lastSeen = seen,
                        arrivals = arrivals,
                        waitingAgain = e.waitingAgain && v.phase == OperationPhase.WAITING,
                    )
                }
                // Whoever asks with the invitation comes in, waiting for a group: as soon as its name is read here.
                if (v.mayInvite && v.phase == OperationPhase.PLACED) {
                    for (request in v.joinRequests) {
                        if (request.name == null || !admitting.add(request.member)) continue
                        scope.launch {
                            val rejection = controller.admit(request.member)
                            EventLog.event("operation.admit", "refused" to rejection)
                        }
                    }
                }
            }
        }

        private fun log(line: String) {
            Log.i(LOG_TAG, "operation-screens $line")
        }

        companion object {
            private const val PREFS = "operation"
            private const val KEY_SHARING = "sharing"
            private const val KEY_REMINDED = "reminded"
            private const val DEMO_FOLDER = "operation-demo"
            private const val REQUESTS = 8
            private const val SETTLE_MS = 1_200L
            private const val MINUTE = 60_000L
            private const val SECOND_MS = 1_000L
            private const val DEFAULT_NAME = "ToolMap"
            const val LOG_TAG = "ToolMapOperation"
            private const val KEY_LEAVING = "leaving"
            private const val KEY_UNHEARD = "departure_unheard"
            private val IN_OPERATION =
                setOf(OperationPhase.ASKING, OperationPhase.WAITING, OperationPhase.PLACED, OperationPhase.LEAVING)

            private fun Fix.toPeerFix(): PeerFix =
                PeerFix(
                    lat = lat,
                    lon = lon,
                    altM = ele?.let { Math.round(it).toInt() },
                    accuracyM = accuracyMeters?.let { Math.round(it).toInt() },
                    speedMs = speedMps,
                    timeSec = (if (timeMillis > 0) timeMillis else System.currentTimeMillis()) / MS_PER_S,
                )

            private const val MS_PER_S = 1000L
        }
    }
