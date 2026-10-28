package fr.toolmap.maps

import android.app.Application
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.KeyboardType
import fr.toolmap.ui.theme.AlertDialog
import fr.toolmap.ui.theme.TextButton
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Temporary probe (P30.2, 2026-10-28): which part of the width dialog never lets Robolectric idle (60 s of
 * `AppNotIdleException` for a test that hangs). One hypothesis per test; removed once the cause is known.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h2000dp")
class DialogIdleProbeTest {
    @get:Rule
    val rule = createComposeRule()

    private fun dialog(
        initial: String,
        number: Boolean,
    ) {
        rule.setContent {
            ToolMapTheme {
                var text by remember { mutableStateOf(initial) }
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Largeur") },
                    text = {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            keyboardOptions =
                                if (number) {
                                    KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                    )
                                } else {
                                    KeyboardOptions.Default
                                },
                        )
                    },
                    confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                )
            }
        }
        rule.onNodeWithText("OK").assertExists()
    }

    @Test
    fun `A text field holding a value`() = dialog("500", number = false)

    @Test
    fun `B text field holding a value, number keyboard`() = dialog("500", number = true)

    @Test
    fun `C empty text field, number keyboard`() = dialog("", number = true)

    @Test
    fun `D number field opened by a touch`() {
        rule.setContent {
            ToolMapTheme {
                var open by remember { mutableStateOf(false) }
                var text by remember { mutableStateOf("500") }
                TextButton(onClick = { open = true }) { Text("Ouvrir") }
                if (open) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Largeur") },
                        text = {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        },
                        confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                    )
                }
            }
        }
        rule.onNodeWithText("Ouvrir").performClick()
        rule.onNodeWithText("OK").assertExists()
    }
}
