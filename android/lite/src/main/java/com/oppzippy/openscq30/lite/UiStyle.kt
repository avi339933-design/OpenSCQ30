package com.oppzippy.openscq30.lite
 
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
 
/**
 * The look of the app, with the colors and sizes of the original Soundcore app (its light theme): page background
 * F5F8FA, white cards with 24dp corners, dark text 333333 and gray text 999999, the brand color 00CBEB and the blue
 * gradient (329DFF to 53D6FF) of the selected buttons.
 * Every clickable view gets a clear frame while it has keyboard focus, because the phone has no touch screen.
 */
object UiStyle {
    val blue = 0xFF00CBEB.toInt() // the brand color
    val blueDark = 0xFF139DC9.toInt() // the frame of the view that has the focus
    val blueLight = 0xFFE6FAFD.toInt() // the brand color at 10% on white
    val background = 0xFFF5F8FA.toInt()
    val card = 0xFFFFFFFF.toInt()
    val card5 = 0xFFF5F8FA.toInt() // the unselected buttons and cards inside a white card
    val textPrimary = 0xFF333333.toInt()
    val textSecondary = 0xFF999999.toInt()
    val divider = 0xFFECECED.toInt() // 0F131A at 8% on white
    val track = 0xFFE8EBEE.toInt() // the unfilled part of a bar
 
    // The blue gradient of the selected buttons (light on the left).
    val gradient = intArrayOf(0xFF53D6FF.toInt(), 0xFF329DFF.toInt())
 
    fun dp(context: Context, value: Float): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
 
    fun rounded(fill: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.cornerRadius = radiusPx
        drawable.setColor(fill)
        if (strokePx > 0) drawable.setStroke(strokePx, strokeColor)
        return drawable
    }
 
    // A rounded rectangle in the blue gradient.
    fun gradientRounded(radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable {
        val drawable = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, gradient)
        drawable.cornerRadius = radiusPx
        if (strokePx > 0) drawable.setStroke(strokePx, strokeColor)
        return drawable
    }
 
    // Normal / focused (frame) / pressed backgrounds.
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
        card.background = rounded(UiStyle.card, dp(context, 24f).toFloat())
        card.setPadding(dp(context, 12f), dp(context, 8f), dp(context, 12f), dp(context, 8f))
        return card
    }
 
    fun cardParams(context: Context): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val side = dp(context, 12f)
        params.setMargins(side, dp(context, 6f), side, dp(context, 6f))
        return params
    }
 
    // The title of a group of settings (16 bold, like the card titles of the original app).
    fun sectionHeader(context: Context, title: String): TextView {
        val view = TextView(context)
        view.text = title
        view.textSize = 16f
        view.setTypeface(view.typeface, Typeface.BOLD)
        view.setTextColor(textPrimary)
        view.setPadding(dp(context, 12f), dp(context, 12f), dp(context, 12f), dp(context, 4f))
        return view
    }
 
    // The small title above a list of items, like "קולות" above the equalizer presets
    // (item_binder_sound_title_layout): 14sp, gray, not bold.
    fun listTitle(context: Context, title: String): TextView {
        val view = TextView(context)
        view.text = title
        view.textSize = 14f
        view.setTextColor(textSecondary)
        view.setPadding(dp(context, 12f), dp(context, 16f), dp(context, 12f), dp(context, 4f))
        return view
    }
 
    fun divider(context: Context): View {
        val view = View(context)
        view.setBackgroundColor(divider)
        return view
    }
 
    fun dividerParams(context: Context): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1f))
 
    // Filled brand-color button (round ends).
    fun primaryButton(context: Context, label: String, onClick: () -> Unit): Button {
        val button = Button(context)
        button.text = label
        button.textSize = 14f
        button.setTextColor(Color.WHITE)
        button.background = focusable(context, blue, 19f)
        button.minHeight = dp(context, 38f)
        button.minimumHeight = dp(context, 38f)
        button.setPadding(dp(context, 12f), dp(context, 6f), dp(context, 12f), dp(context, 6f))
        button.setOnClickListener { onClick() }
        return button
    }
 
    // Text in the brand color without a fill.
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
 
    // A rounded option button: the blue gradient when selected, light gray otherwise (12dp corners, like the original).
    fun pill(context: Context, label: String, onClick: () -> Unit): Button {
        val button = Button(context)
        button.text = label
        button.textSize = 14f
        button.minHeight = dp(context, 44f)
        button.minimumHeight = dp(context, 44f)
        button.minWidth = 0
        button.minimumWidth = 0
        button.setOnClickListener { onClick() }
        setPillSelected(context, button, false)
        return button
    }
 
    fun setPillSelected(context: Context, button: Button, selected: Boolean) {
        val radius = dp(context, 12f).toFloat()
        val stroke = dp(context, 2f)
        val states = StateListDrawable()
        if (selected) {
            states.addState(intArrayOf(android.R.attr.state_focused), gradientRounded(radius, blueDark, stroke))
            states.addState(intArrayOf(android.R.attr.state_pressed), rounded(blueLight, radius, blue, stroke))
            states.addState(intArrayOf(), gradientRounded(radius))
        } else {
            states.addState(intArrayOf(android.R.attr.state_focused), rounded(card5, radius, blueDark, stroke))
            states.addState(intArrayOf(android.R.attr.state_pressed), rounded(blueLight, radius, blue, stroke))
            states.addState(intArrayOf(), rounded(card5, radius))
        }
        button.background = states
        button.setTextColor(if (selected) Color.WHITE else textPrimary)
        button.setPadding(dp(context, 12f), dp(context, 6f), dp(context, 12f), dp(context, 6f))
    }
 
    class RowViews(val root: LinearLayout, val value: TextView)
 
    // A list row: name (16), current value underneath (14) and an arrow. Opens something when clicked.
    fun row(context: Context, title: String, onClick: () -> Unit): RowViews {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.HORIZONTAL
        root.gravity = Gravity.CENTER_VERTICAL
        root.isFocusable = true
        root.isClickable = true
        root.background = focusable(context, card, 8f)
        root.setPadding(dp(context, 8f), dp(context, 12f), dp(context, 8f), dp(context, 12f))
 
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        val titleView = TextView(context)
        titleView.text = title
        titleView.textSize = 16f
        titleView.setTextColor(textPrimary)
        val valueView = TextView(context)
        valueView.textSize = 14f
        valueView.setTextColor(textSecondary)
        column.addView(titleView)
        column.addView(valueView)
        root.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
 
        // The arrow is drawn (not a text character), so it looks the same on every Android version.
        root.addView(OfficialIconView(context, OfficialIcon.ARROW_RIGHT, textSecondary), LinearLayout.LayoutParams(dp(context, 18f), dp(context, 18f)))
 
        root.setOnClickListener { onClick() }
        return RowViews(root, valueView)
    }
}
 
