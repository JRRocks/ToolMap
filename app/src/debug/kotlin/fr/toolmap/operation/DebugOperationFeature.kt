package fr.toolmap.operation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.toolmap.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Singleton

/** The « Opération » mode of the debug build type (P39.8, ADR-130): its screens over [OperationSession]. */
@Singleton
class DebugOperationFeature
    @Inject
    constructor(
        private val session: OperationSession,
    ) : OperationFeature {
        override val active: StateFlow<Boolean> get() = session.active

        override val requests: Flow<OperationRequest> get() = session.requests

        override fun onLink(link: String): Boolean = session.onLink(link)

        @Composable
        override fun shareEntry(onCreate: () -> Unit): ShareOperationEntry =
            ShareOperationEntry(
                startHint = stringResource(R.string.op_entry_hint),
                createLabel = stringResource(R.string.op_entry_create_friends),
                joinTitle = stringResource(R.string.op_entry_join_title),
                card = { OperationCard(onCreate) },
                menuItems = { close -> DemoMenuItem(session, close) },
                onCode = session::onCode,
            )

        @Composable
        override fun ShareDialogs(setName: () -> String) = ShareWindows(session, setName)

        @Composable
        override fun OperationScreen(onBack: () -> Unit) = OperationRoot(session, onBack)

        @Composable
        override fun CreateScreen(
            defaultName: () -> String,
            onClose: () -> Unit,
            onCreated: () -> Unit,
        ) = CreatePage(session, defaultName, onClose, onCreated)

        override fun dump(
            args: Array<out String>,
            writer: PrintWriter,
        ): Boolean = OperationScenes.dump(session, args, writer)
    }

/** Binds the « Opération » mode in the debug build type: the `Optional` of [OperationFeatureModule] is filled here only. */
@Module
@InstallIn(SingletonComponent::class)
abstract class OperationDebugModule {
    @Binds
    abstract fun operationFeature(feature: DebugOperationFeature): OperationFeature
}
