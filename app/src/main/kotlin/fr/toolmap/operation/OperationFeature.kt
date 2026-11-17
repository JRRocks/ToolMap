package fr.toolmap.operation

import androidx.compose.runtime.Composable
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.PrintWriter

/**
 * The « Opération » mode as the rest of the app sees it (P39.8, ADR-130): a few hooks into the screen of the sharing
 * and into [fr.toolmap.MainActivity]. Its screens, texts and demonstration live in the sources of the debug build type
 * (`app/src/debug`), bound by Hilt there: a release or a benchmark build has none of it, and gets no implementation
 * (an empty `Optional`). The callers also check `BuildConfig.OPERATION_MODE`, true for the debug build type only.
 */
interface OperationFeature {
    /** An operation is open on this phone (or the demonstration runs): the screen of the sharing is the operation's. */
    val active: StateFlow<Boolean>

    /** Where to go, asked from outside the screens: a link opened, the demonstration started, a capture of the CI. */
    val requests: Flow<OperationRequest>

    /** A link opened from another app; `true` when it was an invitation to an operation (its question is shown). */
    fun onLink(link: String): Boolean

    /** What the screen of the sharing shows of this mode out of a group: the card « Opération » and its words. */
    @Composable
    fun shareEntry(onCreate: () -> Unit): ShareOperationEntry

    /**
     * The windows of this mode over the screen of the sharing (« Rejoindre l'opération ? », what happened); [setName] is
     * the name set in the app, empty when none is.
     */
    @Composable
    fun ShareDialogs(setName: () -> String)

    /** The screen of the operation open on this phone. */
    @Composable
    fun OperationScreen(onBack: () -> Unit)

    /** « Nouvelle opération »; [onCreated] once it exists, its invitation then shows on the screen of the operation. */
    @Composable
    fun CreateScreen(
        defaultName: () -> String,
        onClose: () -> Unit,
        onCreated: () -> Unit,
    )

    /** `adb shell dumpsys activity fr.toolmap/.MainActivity operation-screens …`; `true` when the arguments were its. */
    fun dump(
        args: Array<out String>,
        writer: PrintWriter,
    ): Boolean
}

/** A screen to open, asked by [OperationFeature.requests]. */
enum class OperationRequest {
    /** The screen of the sharing: the operation's when one is open, else « Entre amis » and its card. */
    SHARE,

    /** « Nouvelle opération ». */
    CREATE,
}

/**
 * What the screen of the sharing shows out of a group when the « Opération » mode exists (P39.8, `tour2/01-entree-A`):
 * its texts name the simple mode « Entre amis », and the card « Opération » comes under the buttons that join.
 */
class ShareOperationEntry(
    /** « Entre amis : créez un groupe et invitez les autres par un code, ou rejoignez celui d'un autre. » */
    val startHint: String,
    /** « Créer un groupe entre amis ». */
    val createLabel: String,
    /** « Rejoindre un groupe ou une opération ». */
    val joinTitle: String,
    /** The card « Opération » and its button. */
    val card: @Composable () -> Unit,
    /** Entries of the ⋮ menu (the demonstration, debug only); `close` closes the menu. */
    val menuItems: @Composable (close: () -> Unit) -> Unit,
    /** A scanned or pasted code; `true` when it was an invitation to an operation (it is then handled here). */
    val onCode: (String) -> Boolean,
)

/** No implementation outside the debug build type: Hilt gives an empty `Optional<OperationFeature>` there. */
@Module
@InstallIn(SingletonComponent::class)
abstract class OperationFeatureModule {
    @BindsOptionalOf
    abstract fun operationFeature(): OperationFeature
}
