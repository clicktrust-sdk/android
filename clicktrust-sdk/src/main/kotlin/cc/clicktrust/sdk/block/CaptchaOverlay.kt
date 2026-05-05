package cc.clicktrust.sdk.block

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import cc.clicktrust.sdk.internal.LifecycleTracker
import cc.clicktrust.sdk.internal.Logger
import cc.clicktrust.sdk.models.ChallengeProof

/**
 * Modal Dialog that hosts the captcha challenge.
 *
 * UI is built programmatically — no resources, no XML layouts —
 * because shipping resources triggers manifest merges and resource
 * R-class collisions in consumer apps. The visual is intentionally
 * minimal: title, status line, progress spinner during PoW, then five
 * coloured buttons for the tap-target step.
 *
 * Threading:
 *   - Construction must happen on the main thread (it touches Views).
 *   - PoW runs on a background daemon Thread.
 *   - Completion callback fires on the main thread for UI safety.
 */
internal class CaptchaOverlay(
    private val challenge: CaptchaChallenge,
    private val message: String?,
    private val onCompleted: (ChallengeProof?) -> Unit,
) {

    private var dialog: Dialog? = null
    private var solverThread: Thread? = null
    @Volatile private var cancelled: Boolean = false
    private var startedAt: Long = 0L
    private var pow: Long? = null

    /**
     * Present using the current foreground activity (if any). Falls
     * back to a no-op + null callback if no activity is available
     * (e.g. SDK was triggered from a Service). The lifecycle tracker
     * caches the most recently resumed Activity for exactly this
     * reason.
     */
    fun present() {
        Handler(Looper.getMainLooper()).post {
            val activity = LifecycleTracker.currentActivity()
            if (activity == null || activity.isFinishing) {
                Logger.w("CaptchaOverlay: no foreground activity; skipping challenge")
                onCompleted(null)
                return@post
            }
            startedAt = System.currentTimeMillis()
            showDialog(activity)
            startSolver()
        }
    }

    private fun showDialog(activity: Activity) {
        val ctx: Context = activity
        val d = Dialog(ctx).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#CC000000")))
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(ctx, 24), dp(ctx, 24), dp(ctx, 24), dp(ctx, 24))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(ctx, 12).toFloat()
            }
        }

        root.addView(TextView(ctx).apply {
            text = "Verify you're human"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.parseColor("#111111"))
            setPadding(0, 0, 0, dp(ctx, 8))
        })

        val statusView = TextView(ctx).apply {
            text = message ?: "Solving security puzzle…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#444444"))
            setPadding(0, 0, 0, dp(ctx, 16))
            id = STATUS_VIEW_ID
        }
        root.addView(statusView)

        val progress = ProgressBar(ctx).apply {
            id = PROGRESS_VIEW_ID
            isIndeterminate = true
        }
        root.addView(progress)

        val palette = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            id = PALETTE_VIEW_ID
            setPadding(0, dp(ctx, 16), 0, 0)
        }
        for (color in CaptchaChallenge.TargetColor.values()) {
            palette.addView(makeColorButton(ctx, color))
        }
        root.addView(palette)

        d.setContentView(
            root,
            ViewGroup.LayoutParams(
                (activity.resources.displayMetrics.widthPixels * 0.85f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        )
        d.show()
        dialog = d
    }

    private fun makeColorButton(ctx: Context, color: CaptchaChallenge.TargetColor): Button {
        return Button(ctx).apply {
            text = ""  // Colour-only — text would change between locales and bias the test
            contentDescription = color.name.lowercase() // accessibility hint
            background = GradientDrawable().apply {
                setColor(toAndroidColor(color))
                cornerRadius = dp(ctx, 8).toFloat()
            }
            val side = dp(ctx, 56)
            layoutParams = LinearLayout.LayoutParams(side, side).apply {
                marginEnd = dp(ctx, 8)
            }
            setOnClickListener { onColorTapped(color) }
        }
    }

    private fun toAndroidColor(c: CaptchaChallenge.TargetColor): Int = when (c) {
        CaptchaChallenge.TargetColor.RED -> Color.parseColor("#E11D48")
        CaptchaChallenge.TargetColor.BLUE -> Color.parseColor("#2563EB")
        CaptchaChallenge.TargetColor.GREEN -> Color.parseColor("#16A34A")
        CaptchaChallenge.TargetColor.YELLOW -> Color.parseColor("#EAB308")
        CaptchaChallenge.TargetColor.PURPLE -> Color.parseColor("#9333EA")
    }

    private fun startSolver() {
        solverThread = Thread({
            val result = challenge.solveProofOfWork(shouldStop = { cancelled })
            Handler(Looper.getMainLooper()).post {
                if (cancelled) return@post
                if (result == null) {
                    finishWith(null, "couldn't solve")
                    return@post
                }
                pow = result
                showColorPalette()
            }
        }, "ClickTrust-PoW").apply { isDaemon = true; start() }
    }

    private fun showColorPalette() {
        val d = dialog ?: return
        val root = (d.window?.decorView as? ViewGroup) ?: return
        d.findViewById<View>(PROGRESS_VIEW_ID)?.visibility = View.GONE
        d.findViewById<TextView>(STATUS_VIEW_ID)?.text =
            "Tap the ${challenge.targetColor.name.lowercase()} square"
        d.findViewById<View>(PALETTE_VIEW_ID)?.visibility = View.VISIBLE
        // Keep `root` reference live to satisfy lint and avoid Dialog
        // GC'ing its decor view between layout passes on slow devices.
        root.requestLayout()
    }

    private fun onColorTapped(color: CaptchaChallenge.TargetColor) {
        val nonce = pow ?: return  // Defensive — palette only shows after solve
        if (color != challenge.targetColor) {
            // Wrong tap — keep the dialog up; the analyst-side view of
            // a 5+ wrong-tap session is already a strong bot signal.
            dialog?.findViewById<TextView>(STATUS_VIEW_ID)?.text =
                "That wasn't ${challenge.targetColor.name.lowercase()}. Try again."
            return
        }
        val proof = ChallengeProof(
            token = challenge.token,
            nonce = nonce,
            tappedColor = color.wire,
            solveMs = System.currentTimeMillis() - startedAt,
        )
        finishWith(proof, "ok")
    }

    private fun finishWith(proof: ChallengeProof?, reason: String) {
        Logger.d("CaptchaOverlay finished: $reason")
        cancelled = true
        try { dialog?.dismiss() } catch (_: Throwable) {}
        dialog = null
        try { onCompleted(proof) } catch (t: Throwable) {
            Logger.w("CaptchaOverlay onCompleted threw", t)
        }
    }

    fun dismiss() = finishWith(null, "external dismiss")

    private fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    companion object {
        // Synthetic view IDs — picked to avoid collision with consumer
        // R.id values. Negative IDs are valid in Android and never
        // appear in generated R.java.
        private const val STATUS_VIEW_ID = -0x10000001
        private const val PROGRESS_VIEW_ID = -0x10000002
        private const val PALETTE_VIEW_ID = -0x10000003
    }
}
