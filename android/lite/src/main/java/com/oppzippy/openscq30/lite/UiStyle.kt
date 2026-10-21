package com.oppzippy.openscq30.lite
 
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
 
/**
 * The look of the app: light background, white rounded cards and light blue accents, like the original Soundcore app.
 * Every clickable view gets a clear blue frame while it has keyboard focus, because the phone has no touch screen.
 */
object UiStyle {
    val blue = 0xFF18B4EC.toInt()
    val blueDark = 0xFF0E8FC0.toInt()
    val blueLight = 0xFFE3F4FD.toInt()
    val background = 0xFFF3F6F9.toInt()
    val card = 0xFFFFFFFF.toInt()
    val textPrimary = 0xFF1F2933.toInt()
    val textSecondary = 0xFF7B8794.toInt()
    val divider = 0xFFE6E9ED.toInt()
    val track = 0xFFDADDE1.toInt()
 
    fun dp(context: Context, value: Float): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
 
    fun rounded(fill: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.cornerRadius = radiusPx
        drawable.setColor(fill)
        if (strokePx > 0) drawable.setStroke(strokePx, strokeColor)
        return drawable
    }
 
    // Normal / focused (blue frame) / pressed backgrounds.
    fun focusable(context: Context, fill: Int, radiusDp: Float = 8f): StateListDrawable {
        val radius = dp(context, radiusDp).toFloat()
        val stroke = dp(context, 2f)
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_focused), rounded(fill, radius, blue, stroke))
        states.addState(intArrayOf(android.R.attr.state_pressed), rounded(blueLight, radius, blue, stroke))
        states.addState(intArrayOf(), rounded(fill, radius))
        return states
    }
 
    fun card(context: Context): LinearLayout {
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(UiStyle.card, dp(context, 12f).toFloat())
        val padding = dp(context, 8f)
        card.setPadding(padding, padding, padding, padding)
        return card
    }
 
    fun cardParams(context: Context): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val side = dp(context, 8f)
        params.setMargins(side, dp(context, 3f), side, dp(context, 3f))
        return params
    }
 
    fun sectionHeader(context: Context, title: String): TextView {
        val view = TextView(context)
        view.text = title
        view.textSize = 12f
        view.setTextColor(textSecondary)
        view.setPadding(dp(context, 14f), dp(context, 10f), dp(context, 14f), dp(context, 2f))
        return view
    }
 
    fun divider(context: Context): View {
        val view = View(context)
        view.setBackgroundColor(divider)
        return view
    }
 
    fun dividerParams(context: Context): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1f))
 
    // Filled blue button.
    fun primaryButton(context: Context, label: String, onClick: () -> Unit): Button {
        val button = Button(context)
        button.text = label
        button.textSize = 13f
        button.setTextColor(Color.WHITE)
        button.background = focusable(context, blue, 8f)
        button.minHeight = dp(context, 38f)
        button.minimumHeight = dp(context, 38f)
        button.setPadding(dp(context, 8f), dp(context, 6f), dp(context, 8f), dp(context, 6f))
        button.setOnClickListener { onClick() }
        return button
    }
 
    // Blue text without a fill (used in the top bar).
    fun textButton(context: Context, label: String, onClick: () -> Unit): TextView {
        val view = TextView(context)
        view.text = label
        view.textSize = 14f
        view.gravity = Gravity.CENTER
        view.setTextColor(blue)
        view.isFocusable = true
        view.isClickable = true
        view.background = focusable(context, card, 8f)
        view.setPadding(dp(context, 8f), dp(context, 10f), dp(context, 8f), dp(context, 10f))
        view.setOnClickListener { onClick() }
        return view
    }
 
    // A rounded option button: filled blue when selected, white with an outline otherwise.
    fun pill(context: Context, label: String, onClick: () -> Unit): Button {
        val button = Button(context)
        button.text = label
        button.textSize = 12f
        button.minHeight = dp(context, 36f)
        button.minimumHeight = dp(context, 36f)
        button.minWidth = 0
        button.minimumWidth = 0
        button.setOnClickListener { onClick() }
        setPillSelected(context, button, false)
        return button
    }
 
    fun setPillSelected(context: Context, button: Button, selected: Boolean) {
        val radius = dp(context, 18f).toFloat()
        val stroke = dp(context, 2f)
        val fill = if (selected) blue else card
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_focused), rounded(fill, radius, blueDark, stroke))
        states.addState(intArrayOf(android.R.attr.state_pressed), rounded(blueLight, radius, blue, stroke))
        states.addState(intArrayOf(), rounded(fill, radius, track, dp(context, 1f)))
        button.background = states
        button.setTextColor(if (selected) Color.WHITE else textPrimary)
        button.setPadding(dp(context, 8f), dp(context, 6f), dp(context, 8f), dp(context, 6f))
    }
 
    class RowViews(val root: LinearLayout, val value: TextView)
 
    // A list row: name, current value underneath and an arrow. Opens something when clicked.
    fun row(context: Context, title: String, onClick: () -> Unit): RowViews {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.HORIZONTAL
        root.gravity = Gravity.CENTER_VERTICAL
        root.isFocusable = true
        root.isClickable = true
        root.background = focusable(context, card, 8f)
        root.setPadding(dp(context, 8f), dp(context, 10f), dp(context, 8f), dp(context, 10f))
 
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        val titleView = TextView(context)
        titleView.text = title
        titleView.textSize = 14f
        titleView.setTextColor(textPrimary)
        val valueView = TextView(context)
        valueView.textSize = 12f
        valueView.setTextColor(textSecondary)
        column.addView(titleView)
        column.addView(valueView)
        root.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
 
        val arrow = TextView(context)
        arrow.text = "‹"
        arrow.textSize = 22f
        arrow.setTextColor(textSecondary)
        root.addView(arrow)
 
        root.setOnClickListener { onClick() }
        return RowViews(root, valueView)
    }
}
 
