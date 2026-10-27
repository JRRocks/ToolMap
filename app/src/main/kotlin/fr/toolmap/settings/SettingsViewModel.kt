package fr.toolmap.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.toolmap.core.settings.AppSettings
import fr.toolmap.data.settings.SettingsStore
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** The settings and the way to change them; the store saves and publishes every change. */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val store: SettingsStore,
    ) : ViewModel() {
        val settings: StateFlow<AppSettings> = store.settings

        fun update(transform: (AppSettings) -> AppSettings) = store.update(transform)
    }
