package fr.toolmap.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

        /**
         * The category open on its page, null on the list (P29.9). Kept here, with the search, so that Back from
         * « Sauvegarde et restauration » or from « Cartes multi-zoom » comes back to the page they were opened from.
         */
        var category by mutableStateOf<SettingsCategory?>(null)

        /** What is typed in « Rechercher un réglage ». */
        var query by mutableStateOf("")

        fun update(transform: (AppSettings) -> AppSettings) = store.update(transform)

        /** The list of the categories, with no search: where « Réglages… » of the menu opens. */
        fun openList() {
            category = null
            query = ""
        }
    }
