package kr.roadalert

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

object Ink {
    val night = Color.rgb(12, 27, 32)
    val panel = Color.rgb(23, 45, 50)
    val panelLight = Color.rgb(36, 62, 67)
    val white = Color.rgb(238, 247, 246)
    val muted = Color.rgb(173, 197, 199)
    val mint = Color.rgb(138, 233, 212)
    val amber = Color.rgb(255, 202, 113)
}
fun Context.dp(n: Int) = (resources.displayMetrics.density * n).roundToInt()
fun Context.label(value: String, sp: Int, color: Int = Ink.white, bold: Boolean = false) =
    TextView(this).apply {
        text = value; textSize = sp.toFloat(); setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = true
    }
fun Context.rounded(color: Int, radius: Int = 16) = GradientDrawable().apply {
    setColor(color); cornerRadius = dp(radius).toFloat()
}
fun Context.action(value: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
    text = value; isAllCaps = false; textSize = 18f; gravity = Gravity.CENTER
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    background = rounded(if (primary) Ink.mint else Ink.panelLight, 12)
    setTextColor(if (primary) Ink.night else Ink.white); minHeight = dp(54); minimumHeight = dp(54)
    setPadding(dp(14), dp(8), dp(14), dp(8)); setOnClickListener { click() }
}
fun LinearLayout.stack(view: View, top: Int = 0) {
    addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(top) })
}
fun LinearLayout.bar(vararg buttons: View) {
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    buttons.forEachIndexed { i, button ->
        row.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = context.dp(8) })
    }
    stack(row, 9)
}

/** Schematic only. The single line expresses distance along one directed segment. */
class ApproachView(context: Context, val meters: Int?, private val onPaint: (() -> Unit)? = null) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var painted = false
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(80))
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val x1 = 16 * d; val x2 = width - 18 * d; val y = 28 * d
        p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 5 * d; p.color = Ink.panelLight
        c.drawLine(x1, y, x2, y, p)
        val end = x1 + (x2 - x1) * 0.81f
        p.color = Ink.mint; c.drawLine(x1, y, if (meters == null) x1 + 28 * d else end, y, p)
        p.color = Ink.panel; c.drawCircle(x1, y, 10 * d, p)
        p.color = Ink.mint; c.drawCircle(x1, y, 6 * d, p)
        p.textSize = 14 * d; p.typeface = Typeface.DEFAULT_BOLD; c.drawText("내 위치", x1 - 10 * d, y + 32 * d, p)
        if (meters != null) {
            p.color = Ink.amber; c.drawCircle(end, y, 8 * d, p)
            p.textAlign = Paint.Align.RIGHT; c.drawText("제보 지점", end + 16 * d, y + 32 * d, p)
            p.textAlign = Paint.Align.LEFT
        }
        if (!painted && onPaint != null) { painted = true; onPaint.invoke() }
    }
}
