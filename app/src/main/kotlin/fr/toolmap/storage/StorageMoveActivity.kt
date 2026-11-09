package fr.toolmap.storage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import fr.toolmap.MainActivity
import fr.toolmap.R
import fr.toolmap.core.problem.Problems
import fr.toolmap.data.tiles.TileLocation
import java.io.IOException

/**
 * The screen of a move of the offline maps (ADR-042). The manifest gives it its own process, which opens no tile file: the
 * copy runs here, then the app opens again on the folder in use. Plain views, as the crash report, so that no screen or
 * map of the app is started meanwhile. Its configuration changes are handled in place: a second start would copy twice.
 */
class StorageMoveActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var continueButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (TileLocation(filesDir).requestedMove() == null) {
            openApp()
            return
        }
        setContentView(layout())
        Thread({ move() }, "tile-move").start()
    }

    private fun move() {
        try {
            TileMover(this).execute { copied, total -> main.post { showProgress(copied, total) } }
            main.post { openApp() }
        } catch (e: Exception) {
            // A folder that went away (card out) or a copy that failed is expected; anything else is a bug.
            if (e is IOException || e is IllegalStateException) {
                Problems.expected("storage.move", e)
            } else {
                Problems.report("storage.move", e)
            }
            main.post { failed() }
        }
    }

    private fun showProgress(
        copied: Long,
        total: Long,
    ) {
        message.text =
            getString(
                R.string.storage_move_progress,
                Formatter.formatShortFileSize(this, copied),
                Formatter.formatShortFileSize(this, total),
            )
        progress.isIndeterminate = total == 0L
        progress.progress = if (total == 0L) 0 else (copied * PROGRESS_STEPS / total).toInt()
    }

    private fun failed() {
        message.setText(R.string.storage_move_failed)
        progress.visibility = View.GONE
        continueButton.visibility = View.VISIBLE
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun layout(): LinearLayout {
        val pad = (PADDING_DP * resources.displayMetrics.density).toInt()
        message =
            TextView(this).apply {
                setText(R.string.storage_move_preparing)
                textSize = TEXT_SP
            }
        progress =
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                max = PROGRESS_STEPS
            }
        continueButton =
            Button(this).apply {
                setText(R.string.storage_move_open)
                visibility = View.GONE
                setOnClickListener { openApp() }
            }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(message, LinearLayout.LayoutParams(match, wrap))
            addView(progress, LinearLayout.LayoutParams(match, wrap))
            addView(continueButton, LinearLayout.LayoutParams(wrap, wrap))
        }
    }

    private companion object {
        const val PADDING_DP = 24
        const val TEXT_SP = 17f
        const val PROGRESS_STEPS = 1000
    }
}
