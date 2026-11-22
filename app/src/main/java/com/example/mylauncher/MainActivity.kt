package com.example.mylauncher   // غيّره لاسم الباكدج بتاع مشروعك

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : Activity() {

    data class AppItem(val label: String, val pkg: String, val icon: Drawable)

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val palette = intArrayOf(
        0xFF3E91FF.toInt(), 0xFF9B5CF6.toInt(), 0xFFFF4D8D.toInt(), 0xFFFF5A52.toInt(),
        0xFFFF9F0A.toInt(), 0xFFFFD60A.toInt(), 0xFF30D158.toInt(), 0xFF40C8E0.toInt()
    )
    private val prefs by lazy { getSharedPreferences("aos", Context.MODE_PRIVATE) }

    // الإعدادات
    private var speed = 1f
    private var blurOn = true
    private var blurStrength = 90
    private var bounce = true
    private var haptics = true
    private var accentIdx = 1
    private var iconStyle = 0
    private var iconSize = 1
    private var columns = 4
    private var clockStyle = 1
    private val accent: Int get() = palette[accentIdx]

    // الواجهة
    private lateinit var root: FrameLayout
    private lateinit var home: FrameLayout
    private lateinit var clockBox: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var search: EditText
    private lateinit var grid: GridView
    private var onboardingView: View? = null
    private var settingsView: FrameLayout? = null
    private var sBox: LinearLayout? = null
    private var sScroll: ScrollView? = null

    private var apps = listOf<AppItem>()
    private var shown = listOf<AppItem>()
    private val adapter = AppAdapter()

    // السحب
    private var progress = 0f
    private var drawerOpen = false
    private var dragging = false
    private var startP = 0f
    private var downY = 0f
    private var vt: VelocityTracker? = null
    private var settleSpring: SpringAnimation? = null
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { loadApps() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dur(base: Long) = (base / speed).toLong().coerceAtLeast(1L)
    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
    private fun buzz() { if (haptics) root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }

    private fun put(k: String, v: Any) {
        val e = prefs.edit()
        when (v) {
            is Int -> e.putInt(k, v)
            is Float -> e.putFloat(k, v)
            is Boolean -> e.putBoolean(k, v)
        }
        e.apply()
    }

    private fun loadPrefs() {
        speed = prefs.getFloat("speed", 1f).coerceIn(0.5f, 2.5f)
        blurOn = prefs.getBoolean("blur", true)
        blurStrength = prefs.getInt("blurStrength", 90).coerceIn(30, 150)
        bounce = prefs.getBoolean("bounce", true)
        haptics = prefs.getBoolean("haptics", true)
        accentIdx = prefs.getInt("accent", 1).coerceIn(0, palette.size - 1)
        iconStyle = prefs.getInt("iconStyle", 0).coerceIn(0, 2)
        iconSize = prefs.getInt("iconSize", 1).coerceIn(0, 2)
        columns = prefs.getInt("columns", 4).coerceIn(3, 5)
        clockStyle = prefs.getInt("clockStyle", 1).coerceIn(0, 3)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPrefs()
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        root = FrameLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        home = FrameLayout(this)
        clockBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        home.addView(clockBox, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { topMargin = dp(88) })

        dock = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat() }
        }
        home.addView(dock, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(32)) })

        search = EditText(this).apply {
            hint = "بحث"; setHintTextColor(0xFFBBBBBB.toInt()); setTextColor(Color.WHITE); textSize = 16f
            setSingleLine(); imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.argb(60, 255, 255, 255)) }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { filterApps(s?.toString().orEmpty()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { _, _, _ -> shown.firstOrNull()?.let { launch(it, drawer) }; true }
        }
        val gear = TextView(this).apply {
            text = "⚙"; textSize = 26f; setTextColor(Color.WHITE); setPadding(dp(14), 0, dp(6), 0)
            setOnClickListener { showSettings() }
        }
        val topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(52), dp(16), dp(8))
            addView(search, LinearLayout.LayoutParams(0, WRAP, 1f)); addView(gear)
        }
        grid = GridView(this).apply {
            verticalSpacing = dp(16)
            setPadding(dp(8), dp(8), dp(8), dp(24)); clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            this.adapter = this@MainActivity.adapter
        }
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; alpha = 0f
            addView(topBar); addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        root.addView(home); root.addView(drawer)
        setContentView(root)

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) { if (!drawerOpen && !dragging) { buzz(); showSettings() } }
        })
        home.setOnTouchListener { _, e -> detector.onTouchEvent(e); true }

        applyAppearance()
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        })
        loadApps()
        if (!prefs.getBoolean("setup_done", false)) showOnboarding()
    }

    override fun onDestroy() {
        try { unregisterReceiver(pkgReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }

    // ───────────── المظهر ─────────────
    private fun applyAppearance() {
        renderClock()
        grid.numColumns = columns
        (dock.background as GradientDrawable).setColor(withAlpha(accent, 95))
        adapter.notifyDataSetChanged()
        buildDock()
    }

    private fun tc(fmt: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false, thin: Boolean = false) =
        TextClock(this).apply {
            format12Hour = fmt; format24Hour = fmt.replace("hh", "HH")
            textSize = size; setTextColor(color); gravity = Gravity.CENTER
            typeface = Typeface.create(if (thin) "sans-serif-thin" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            setShadowLayer(8f, 0f, 2f, Color.argb(120, 0, 0, 0)); includeFontPadding = false
        }

    private fun renderClock() {
        clockBox.removeAllViews()
        when (clockStyle) {
            0 -> clockBox.addView(tc("hh:mm", 64f, thin = true))
            1 -> { clockBox.addView(tc("EEEE d MMMM", 16f)); clockBox.addView(tc("hh:mm", 60f, bold = true)) }
            2 -> { clockBox.addView(tc("hh", 84f, bold = true)); clockBox.addView(tc("mm", 84f, accent, bold = true)) }
            else -> clockBox.addView(tc("hh:mm", 40f, accent, bold = true))
        }
    }

    private fun iconFilter(): ColorMatrixColorFilter? = when (iconStyle) {
        1 -> {
            val a = accent
            val g = ColorMatrix().apply { setSaturation(0f) }
            g.postConcat(ColorMatrix(floatArrayOf(
                Color.red(a) / 255f * 1.15f, 0f, 0f, 0f, 0f,
                0f, Color.green(a) / 255f * 1.15f, 0f, 0f, 0f,
                0f, 0f, Color.blue(a) / 255f * 1.15f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f)))
            ColorMatrixColorFilter(g)
        }
        2 -> ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        else -> null
    }

    // ───────────── التطبيقات ─────────────
    private fun loadApps() = thread {
        try {
            val pm = packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(i, 0)
                .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
                .filter { it.pkg != packageName }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
            runOnUiThread { apps = list; filterApps(search.text.toString()); buildDock() }
        } catch (e: Exception) {}
    }

    private fun filterApps(q: String) {
        shown = if (q.isBlank()) apps else apps.filter { it.label.contains(q.trim(), true) }
        adapter.notifyDataSetChanged()
    }

    private fun springScale(v: View, to: Float) {
        listOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y).forEach { p ->
            SpringAnimation(v, p, to).apply {
                spring.dampingRatio = if (bounce) 0.45f else 1f
                spring.stiffness = 500f * speed
                start()
            }
        }
    }

    private fun iconView(app: AppItem, label: Boolean): View {
        val sz = dp(intArrayOf(48, 56, 66)[iconSize])
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        v.addView(ImageView(this).apply { setImageDrawable(app.icon); colorFilter = iconFilter() }, LinearLayout.LayoutParams(sz, sz))
        if (label) v.addView(TextView(this).apply {
            text = app.label; setTextColor(Color.WHITE); textSize = 11f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        })
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> springScale(view, 0.86f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> springScale(view, 1f)
            }
            false
        }
        v.setOnClickListener { launch(app, it) }
        v.setOnLongClickListener { buzz(); showAppMenu(app, it); true }
        return v
    }

    private fun launch(app: AppItem, view: View) {
        try {
            val i = packageManager.getLaunchIntentForPackage(app.pkg) ?: throw ActivityNotFoundException()
            val o = ActivityOptions.makeScaleUpAnimation(view, 0, 0, view.width, view.height)
            startActivity(i, o.toBundle())
        } catch (e: Exception) {
            Toast.makeText(this, "تعذّر فتح التطبيق", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppMenu(app: AppItem, anchor: View) {
        val pinned = dockList().contains(app.pkg)
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, if (pinned) "إزالة من الدوك" else "تثبيت في الدوك")
            menu.add(0, 2, 1, "معلومات التطبيق")
            menu.add(0, 3, 2, "إلغاء التثبيت")
            setOnMenuItemClickListener {
                try {
                    when (it.itemId) {
                        1 -> togglePin(app.pkg)
                        2 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
                        3 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
                    }
                } catch (e: Exception) {}
                true
            }
            show()
        }
    }

    private fun dockList(): List<String> {
        val saved = prefs.getString("dock", null)
        return if (saved == null) apps.take(4).map { it.pkg } else saved.split(",").filter { it.isNotBlank() }
    }

    private fun togglePin(pkg: String) {
        val cur = dockList().toMutableList()
        if (!cur.remove(pkg)) {
            if (cur.size >= 5) { Toast.makeText(this, "الدوك ممتلئ (5 تطبيقات)", Toast.LENGTH_SHORT).show(); return }
            cur.add(pkg)
        }
        prefs.edit().putString("dock", cur.joinToString(",")).apply()
        buildDock()
    }

    private fun buildDock() {
        dock.removeAllViews()
        dockList().mapNotNull { p -> apps.firstOrNull { it.pkg == p } }
            .forEach { dock.addView(iconView(it, false), LinearLayout.LayoutParams(0, WRAP, 1f)) }
        dock.visibility = if (dock.childCount == 0) View.INVISIBLE else View.VISIBLE
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(i: Int) = shown[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, c: View?, p: ViewGroup) = iconView(shown[i], true)
    }

    // ───────────── السحب التفاعلي للدرج (زي iOS) ─────────────
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (settingsView != null || onboardingView != null) return super.dispatchTouchEvent(ev)
        val was = dragging
        handleDrag(ev)
        if (dragging && !was) {
            val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(c); c.recycle()
        }
        return if (dragging || was) true else super.dispatchTouchEvent(ev)
    }

    private fun gridAtTop() = grid.childCount == 0 || (grid.firstVisiblePosition == 0 && grid.getChildAt(0).top >= grid.paddingTop)

    private fun handleDrag(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.rawY; dragging = false
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dy = e.rawY - downY
                if (!dragging && abs(dy) > slop * 2) {
                    val open = !drawerOpen && dy < 0
                    val close = drawerOpen && dy > 0 && gridAtTop()
                    if (open || close) {
                        dragging = true; settleSpring?.cancel(); startP = progress
                        downY = e.rawY; drawer.visibility = View.VISIBLE
                    }
                }
                if (dragging) {
                    progress = (startP - (e.rawY - downY) / dp(420)).coerceIn(0f, 1f)
                    applyProgress(progress)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    val target = when { vy < -900 -> 1f; vy > 900 -> 0f; progress > 0.4f -> 1f; else -> 0f }
                    dragging = false
                    settleTo(target, -vy / dp(420))
                }
                vt?.recycle(); vt = null
            }
        }
    }

    private fun settleTo(target: Float, velocity: Float) {
        settleSpring?.cancel()
        drawerOpen = target >= 1f
        if (drawerOpen) drawer.visibility = View.VISIBLE
        buzz()
        settleSpring = SpringAnimation(FloatValueHolder(progress)).apply {
            setSpring(SpringForce(target).setDampingRatio(if (bounce) 0.72f else 1f)
                .setStiffness((380f * speed * speed).coerceIn(120f, 2500f)))
            setStartVelocity(velocity)
            setMinimumVisibleChange(1f / 500f)
            addUpdateListener { _, value, _ -> progress = value; applyProgress(value) }
            addEndListener { _, canceled, _, _ ->
                if (canceled) return@addEndListener
                progress = target; applyProgress(target)
                if (!drawerOpen) { drawer.visibility = View.GONE; clearSearch() }
            }
            start()
        }
    }

    private fun toggleDrawer(open: Boolean) { drawer.visibility = View.VISIBLE; settleTo(if (open) 1f else 0f, 0f) }

    private fun applyProgress(p: Float) {
        val c = p.coerceIn(0f, 1f)
        drawer.alpha = c
        drawer.translationY = (1f - p) * dp(110)
        drawer.setBackgroundColor(Color.argb((c * (if (blurOn) 110 else 175)).toInt(), 0, 0, 0))
        val s = 1f - 0.12f * c
        home.scaleX = s; home.scaleY = s; home.alpha = 1f - 0.75f * c
        if (Build.VERSION.SDK_INT >= 31) try {
            window.setBackgroundBlurRadius(if (blurOn && windowManager.isCrossWindowBlurEnabled) (c * blurStrength).toInt() else 0)
        } catch (e: Throwable) {}
    }

    private fun clearSearch() {
        search.setText(""); search.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
    }

    // ───────────── عناصر مشتركة ─────────────
    private fun pill(text: String, bg: Int = accent) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setPadding(dp(24), dp(16), dp(24), dp(16))
        background = GradientDrawable().apply { cornerRadius = dp(100).toFloat(); setColor(bg) }
    }

    private fun lbl(t: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.WHITE); setPadding(0, dp(10), 0, dp(6))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun sec(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(accent); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(22), 0, dp(4))
    }

    private fun chips(options: List<String>, sel: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this)
        options.forEachIndexed { i, t ->
            val c = pill(t, if (i == sel) accent else 0xFF333333.toInt()).apply {
                textSize = 13f; setPadding(dp(6), dp(10), dp(6), dp(10)); setOnClickListener { onPick(i) }
            }
            row.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        return row
    }

    private fun sw(t: String, on: Boolean, f: (Boolean) -> Unit) = Switch(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 15f; isChecked = on
        setPadding(0, dp(10), 0, dp(10)); setOnCheckedChangeListener { _, c -> f(c) }
    }

    private fun seekL(f: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { f(p) }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    // ───────────── شاشة الترحيب ─────────────
    private val steps = listOf(
        "مرحباً بك في Aos" to "لانشر سلس وخفيف بواجهة أنيقة وحركات ناعمة.",
        "أنيميشن بروح iOS" to "اسحب الدرج بإصبعك وهو يتبعك، والتطبيقات تفتح بتكبير من مكان الأيقونة.",
        "خصّص كل شيء" to "غيّر الألوان وشكل الساعة وحجم الأيقونات وسرعة الأنيميشن من الإعدادات.",
        "الشاشة الرئيسية والدرج" to "اسحب لفوق للدرج وابحث عن أي تطبيق. اضغط مطولاً على أيقونة للخيارات، وعلى الشاشة الرئيسية للإعدادات.",
        "كل شيء جاهز" to "استمتع بتجربة Aos."
    )

    private fun showOnboarding() {
        var step = 0
        val o = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); isClickable = true; alpha = 0f }
        onboardingView = o
        val title = TextView(this).apply { textSize = 32f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        val body = TextView(this).apply {
            textSize = 16f; setTextColor(0xFFAAAAAA.toInt()); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f); setPadding(0, dp(16), 0, 0)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), 0)
            addView(title); addView(body)
        }
        val dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        repeat(steps.size) {
            dots.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } },
                LinearLayout.LayoutParams(dp(8), dp(8)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        val button = pill("")
        val skip = TextView(this).apply {
            text = "تخطي"; setTextColor(0xFF888888.toInt()); textSize = 15f; setPadding(dp(24), dp(48), dp(24), dp(16))
        }
        fun render() {
            title.text = steps[step].first; body.text = steps[step].second
            button.text = when (step) { 0 -> "هيا نبدأ"; steps.lastIndex -> "ابدأ الاستخدام"; else -> "التالي" }
            skip.visibility = if (step == steps.lastIndex) View.INVISIBLE else View.VISIBLE
            for (i in 0 until dots.childCount)
                (dots.getChildAt(i).background as GradientDrawable).setColor(if (i == step) accent else 0xFF444444.toInt())
        }
        fun done() {
            prefs.edit().putBoolean("setup_done", true).apply(); onboardingView = null
            o.animate().alpha(0f).scaleX(1.15f).scaleY(1.15f).setDuration(dur(450)).withEndAction { root.removeView(o) }.start()
        }
        fun next() {
            if (step == steps.lastIndex) { done(); return }
            content.animate().alpha(0f).translationX(-dp(48).toFloat()).setDuration(dur(160)).withEndAction {
                step++; render(); content.translationX = dp(48).toFloat()
                content.animate().alpha(1f).translationX(0f).setDuration(dur(240)).start()
            }.start()
        }
        button.setOnClickListener { next() }
        skip.setOnClickListener { done() }
        o.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        o.addView(dots, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dp(112) })
        o.addView(button, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(24), 0, dp(24), dp(32)) })
        o.addView(skip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END))
        render(); root.addView(o)
        o.animate().alpha(1f).setDuration(dur(500)).start()
    }

    // ───────────── الإعدادات الكاملة ─────────────
    private fun showSettings() {
        if (settingsView != null) return
        val s = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0)); isClickable = true
            layoutDirection = View.LAYOUT_DIRECTION_RTL; setOnClickListener { hideSettings() }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(32)) }
        val r = dp(28).toFloat()
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isClickable = true; addView(box)
            background = GradientDrawable().apply { setColor(0xFF1C1C1E.toInt()); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        val h = (resources.displayMetrics.heightPixels * 0.82f).toInt()
        s.addView(scroll, FrameLayout.LayoutParams(MATCH, h, Gravity.BOTTOM))
        sBox = box; sScroll = scroll; settingsView = s
        fillSettings()
        root.addView(s)
        scroll.translationY = h.toFloat()
        SpringAnimation(scroll, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            spring.dampingRatio = if (bounce) 0.8f else 1f; spring.stiffness = 350f * speed; start()
        }
    }

    private fun hideSettings() {
        val s = settingsView ?: return
        settingsView = null; sBox = null; sScroll = null
        s.animate().alpha(0f).setDuration(dur(220)).withEndAction { root.removeView(s) }.start()
    }

    private fun refillSettings() {
        val sc = sScroll ?: return
        val y = sc.scrollY
        fillSettings()
        sc.post { sc.scrollTo(0, y) }
    }

    private fun fillSettings() {
        val box = sBox ?: return
        box.removeAllViews()
        box.addView(lbl("إعدادات Aos", 22f, true))

        box.addView(sec("الحركة والأنيميشن"))
        val speedLbl = lbl("")
        val seek = SeekBar(this).apply { max = 8 }
        val setSpeed = { p: Int -> speed = 0.5f + p * 0.25f; speedLbl.text = "سرعة الأنيميشن: ${speed}x"; put("speed", speed) }
        seek.setOnSeekBarChangeListener(seekL(setSpeed))
        seek.progress = ((speed - 0.5f) / 0.25f).toInt().coerceIn(0, 8)
        setSpeed(seek.progress)
        val slower = pill("تبطيء الأنيميشن", 0xFF333333.toInt()).apply {
            textSize = 14f; setOnClickListener { seek.progress = (seek.progress - 1).coerceAtLeast(0) }
        }
        val faster = pill("تسريع الأنيميشن").apply {
            textSize = 14f; setOnClickListener { seek.progress = (seek.progress + 1).coerceAtMost(8) }
        }
        val row = LinearLayout(this)
        row.addView(slower, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
        row.addView(faster, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(speedLbl); box.addView(seek); box.addView(row)
        box.addView(lbl("نمط الحركة"))
        box.addView(chips(listOf("iOS (مرتد)", "ناعم"), if (bounce) 0 else 1) { bounce = it == 0; put("bounce", bounce); refillSettings() })
        box.addView(sw("تفعيل البلور", blurOn) { blurOn = it; put("blur", it) })
        val bl = lbl("")
        val bs = SeekBar(this).apply { max = 6 }
        bs.setOnSeekBarChangeListener(seekL { p -> blurStrength = 30 + p * 20; bl.text = "قوة البلور: $blurStrength"; put("blurStrength", blurStrength) })
        bs.progress = ((blurStrength - 30) / 20).coerceIn(0, 6)
        bl.text = "قوة البلور: $blurStrength"
        box.addView(bl); box.addView(bs)
        box.addView(sw("اهتزاز خفيف", haptics) { haptics = it; put("haptics", it) })

        box.addView(sec("الألوان والأيقونات"))
        box.addView(lbl("لون التطبيق الرئيسي"))
        val dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        palette.forEachIndexed { i, col ->
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(col); if (i == accentIdx) setStroke(dp(3), Color.WHITE)
                }
                setOnClickListener { accentIdx = i; put("accent", i); applyAppearance(); refillSettings() }
            }
            dots.addView(dot, LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(dp(4), dp(8), dp(4), dp(8)) })
        }
        box.addView(dots)
        box.addView(lbl("ألوان الأيقونات"))
        box.addView(chips(listOf("أصلي", "بلون التطبيق", "رمادي"), iconStyle) { iconStyle = it; put("iconStyle", it); applyAppearance(); refillSettings() })
        box.addView(lbl("حجم الأيقونات"))
        box.addView(chips(listOf("صغير", "متوسط", "كبير"), iconSize) { iconSize = it; put("iconSize", it); applyAppearance(); refillSettings() })
        box.addView(lbl("عدد الأعمدة في الدرج"))
        box.addView(chips(listOf("3", "4", "5"), columns - 3) { columns = 3 + it; put("columns", columns); applyAppearance(); refillSettings() })

        box.addView(sec("الساعة"))
        box.addView(lbl("شكل الساعة"))
        box.addView(chips(listOf("رفيع", "عريض + تاريخ", "مكدّس", "مصغّر"), clockStyle) { clockStyle = it; put("clockStyle", it); applyAppearance(); refillSettings() })

        box.addView(sec("متقدم"))
        val lp = { LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) } }
        box.addView(pill("إعادة شاشة الترحيب", 0xFF333333.toInt()).apply {
            textSize = 14f; setOnClickListener { put("setup_done", false); hideSettings(); showOnboarding() }
        }, lp())
        box.addView(pill("استعادة الإعدادات الافتراضية", 0xFF333333.toInt()).apply {
            textSize = 14f; setOnClickListener { resetDefaults() }
        }, lp())
        box.addView(pill("تم").apply { setOnClickListener { hideSettings() } }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(22) })
    }

    private fun resetDefaults() {
        val e = prefs.edit()
        listOf("speed", "blur", "blurStrength", "bounce", "haptics", "accent", "iconStyle", "iconSize", "columns", "clockStyle")
            .forEach { e.remove(it) }
        e.apply(); loadPrefs(); applyAppearance(); refillSettings()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        hideSettings()
        if (drawerOpen) toggleDrawer(false)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (settingsView != null) hideSettings() else if (drawerOpen) toggleDrawer(false)
    }
}
