package fr.toolmap.crash

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fr.toolmap.MainActivity
import fr.toolmap.R

/**
 * The crash report screen. It runs in its own process (`:crash`, see the manifest) because the app's process is dead
 * or dying, and it depends on nothing of the app (no Hilt, no Compose, no map): plain views, so that it opens whatever
 * broke.
 */
class CrashActivity : Activity() {
    private lateinit var report: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        report = CrashReporter.takeForDisplay(this) ?: getString(R.string.crash_none)
        setContentView(content())
    }

    private fun content(): View {
        val margin = dp(16)
        val title =
            TextView(this).apply {
                setText(R.string.crash_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTypeface(typeface, Typeface.BOLD)
            }
        val intro =
            TextView(this).apply {
                setText(R.string.crash_intro)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, dp(8), 0, dp(8))
            }
        val actions =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(action(R.string.crash_copy) { copy() })
                addView(action(R.string.crash_share) { share() })
                addView(action(R.string.crash_restart) { restart() })
            }
        val text =
            TextView(this).apply {
                tag = REPORT_TAG
                text = report
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextIsSelectable(true)
                setPadding(0, dp(8), 0, 0)
            }
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(intro)
                addView(actions)
                addView(
                    ScrollView(this@CrashActivity).apply {
                        addView(text)
                    },
                    LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
                )
            }
        // Drawn edge to edge from Android 15 on: keep the text and the buttons out of the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(margin + bars.left, margin + bars.top, margin + bars.right, margin + bars.bottom)
            insets
        }
        root.setPadding(margin, margin, margin, margin)
        return root
    }

    private fun action(
        label: Int,
        onClick: () -> Unit,
    ) = Button(this).apply {
        setText(label)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
    }

    private fun copy() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.crash_subject), report))
        // Android 13+ confirms a copy by itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun share() {
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_subject))
                .putExtra(Intent.EXTRA_TEXT, report)
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.crash_share))) }
    }

    private fun restart() {
        startActivity(
            Intent(
                this,
                MainActivity::class.java,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** Tag of the view that holds the report (tests). */
        const val REPORT_TAG = "crash_report"

        fun intent(context: Context) = Intent(context, CrashActivity::class.java)
    }
}
