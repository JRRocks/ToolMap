package fr.toolmap.operation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.toolmap.BuildConfig
import fr.toolmap.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The « Opération » mode of every build type (P39.8, ADR-146): its screens over [OperationSession]. The demonstration
 * and the scenes of the captures are testing tools, reached only where [BuildConfig.DEV_TOOLS] is true (debug).
 */
@Singleton
class AppOperationFeature
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
                menuItems = { close -> if (BuildConfig.DEV_TOOLS) DemoMenuItem(session, close) },
                onCode = session::onCode,
            )

        @Composable
        override fun ShareDialogs(setName: () -> String) = ShareWindows(session, setName)

        @Composable
        override fun AppDialogs() = AppWindows(session)

        override fun onAppShown() = session.onAppShown()

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
        ): Boolean = BuildConfig.DEV_TOOLS && OperationScenes.dump(session, args, writer)
    }

/** Binds the « Opération » mode in every build type (ADR-146). */
@Module
@InstallIn(SingletonComponent::class)
abstract class OperationFeatureModule {
    @Binds
    abstract fun operationFeature(feature: AppOperationFeature): OperationFeature
}
