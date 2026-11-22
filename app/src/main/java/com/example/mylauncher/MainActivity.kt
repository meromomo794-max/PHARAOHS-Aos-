package com.example.mylauncher   // غيّره لاسم الباكدج بتاع مشروعك

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import kotlin.concurrent.thread

class MainActivity : Activity() {

    data class AppItem(val label: String, val pkg: String, val icon: Drawable)

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val BLUE = 0xFF3E91FF.toInt()

    private val prefs by lazy { getSharedPreferences("aos", Context.MODE_PRIVATE) }
    private var speed = 1f          // سرعة الأنيميشن
    private var blurOn = true

    private lateinit var root: FrameLayout
    private lateinit var home: FrameLayout
    private lateinit var dock: LinearLayout
    private lateinit var drawer: FrameLayout
    private lateinit var grid: GridView
    private var settingsView: FrameLayout? = null
    private var apps = listOf<AppItem>()
    private var drawerOpen = false
    private var anim: ValueAnimator? = null

    private fun dur(base: Long) = (base / speed).toLong().coerceAtLeast(1L)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speed = prefs.getFloat("speed", 1f)
        blurOn = prefs.getBoolean("blur", true)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        root = FrameLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        home = FrameLayout(this)

        val clock = TextClock(this).apply {
            format12Hour = "hh:mm"; format24Hour = "HH:mm"
            textSize = 64f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            setShadowLayer(8f, 0f, 2f, Color.argb(120, 0, 0, 0))
        }
        home.addView(clock, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { topMargin = dp(96) })

        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat(); setColor(Color.argb(70, 255, 255, 255))
            }
        }
        home.addView(dock, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            setMargins(dp(16), 0, dp(16), dp(32))
        })

        grid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(16)
            setPadding(dp(8), dp(72), dp(8), dp(24))
            clipToPadding = false
            selector = ColorDrawable(Color.TRANSPARENT)
        }
        drawer = FrameLayout(this).apply { visibility = View.GONE; alpha = 0f }
        drawer.addView(grid)

        root.addView(home)
        root.addView(drawer)
        setContentView(root)

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (vy < -1000 && !drawerOpen) { animateDrawer(true); return true }
                if (vy > 1000 && drawerOpen && grid.firstVisiblePosition == 0) { animateDrawer(false); return true }
                return false
            }
            override fun onLongPress(e: MotionEvent) { if (!drawerOpen) showSettings() }
        })
        home.setOnTouchListener { _, e -> detector.onTouchEvent(e); true }
        grid.setOnTouchListener { _, e -> detector.onTouchEvent(e); false }

        loadApps()
        if (!prefs.getBoolean("setup_done", false)) showOnboarding()
    }

    // ───────────── شاشة الترحيب (على طريقة One UI) ─────────────
    private val steps = listOf(
        "مرحباً بك في Aos" to "لانشر سلس وخفيف بواجهة أنيقة وحركات ناعمة.",
        "أنيميشن سلس" to "حركات وبلور ناعمة تشتغل بكفاءة حتى على التليفونات الضعيفة.",
        "تحكّم في السرعة" to "تقدر تسرّع الأنيميشن أو تبطّئه من الإعدادات في أي وقت.",
        "الشاشة الرئيسية والدرج" to "اسحب لفوق لفتح درج التطبيقات، واضغط مطولاً على الشاشة الرئيسية لفتح الإعدادات.",
        "كل شيء جاهز" to "استمتع بتجربة Aos."
    )

    private fun pill(text: String, bg: Int = BLUE) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(16), dp(24), dp(16))
        background = GradientDrawable().apply { cornerRadius = dp(100).toFloat(); setColor(bg) }
    }

    private fun showOnboarding() {
        var step = 0
        val o = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); isClickable = true; alpha = 0f }
        val title = TextView(this).apply {
            textSize = 32f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        val body = TextView(this).apply {
            textSize = 16f; setTextColor(0xFFAAAAAA.toInt()); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f); setPadding(0, dp(16), 0, 0)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
            addView(title); addView(body)
        }
        val dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        repeat(steps.size) {
            dots.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        val button = pill("")
        val skip = TextView(this).apply {
            text = "تخطي"; setTextColor(0xFF888888.toInt()); textSize = 15f
            setPadding(dp(24), dp(48), dp(24), dp(16))
        }

        fun render() {
            title.text = steps[step].first
            body.text = steps[step].second
            button.text = when (step) { 0 -> "هيا نبدأ"; steps.lastIndex -> "ابدأ الاستخدام"; else -> "التالي" }
            skip.visibility = if (step == steps.lastIndex) View.INVISIBLE else View.VISIBLE
            for (i in 0 until dots.childCount)
                (dots.getChildAt(i).background as GradientDrawable).setColor(if (i == step) BLUE else 0xFF444444.toInt())
        }
        fun done() {
            prefs.edit().putBoolean("setup_done", true).apply()
            o.animate().alpha(0f).scaleX(1.15f).scaleY(1.15f).setDuration(dur(450))
                .withEndAction { root.removeView(o) }.start()
        }
        fun next() {
            if (step == steps.lastIndex) { done(); return }
            content.animate().alpha(0f).translationX(-dp(48).toFloat()).setDuration(dur(160)).withEndAction {
                step++; render()
                content.translationX = dp(48).toFloat()
                content.animate().alpha(1f).translationX(0f).setDuration(dur(240))
                    .setInterpolator(DecelerateInterpolator()).start()
            }.start()
        }

        button.setOnClickListener { next() }
        skip.setOnClickListener { done() }
        o.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        o.addView(dots, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dp(112) })
        o.addView(button, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(24), 0, dp(24), dp(32)) })
        o.addView(skip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END))
        render()
        root.addView(o)
        o.animate().alpha(1f).setDuration(dur(500)).start()
    }

    // ───────────── الإعدادات ─────────────
    private fun showSettings() {
        if (settingsView != null) return
        val s = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0)); isClickable = true
            setOnClickListener { hideSettings() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(32))
            isClickable = true
            background = GradientDrawable().apply {
                setColor(0xFF1C1C1E.toInt())
                cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        fun label(t: String, size: Float = 16f) = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.WHITE); setPadding(0, dp(12), 0, dp(8))
        }
        card.addView(label("إعدادات Aos", 22f).apply { typeface = Typeface.DEFAULT_BOLD })

        val speedLabel = label("")
        val seek = SeekBar(this).apply { max = 8 }
        fun setSpeed(p: Int) {
            speed = 0.5f + p * 0.25f
            speedLabel.text = "سرعة الأنيميشن: ${speed}x"
            prefs.edit().putFloat("speed", speed).apply()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { setSpeed(p) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        seek.progress = ((speed - 0.5f) / 0.25f).toInt().coerceIn(0, 8)
        setSpeed(seek.progress)

        val slower = pill("تبطيء الأنيميشن", 0xFF333333.toInt()).apply {
            textSize = 14f; setOnClickListener { seek.progress = (seek.progress - 1).coerceAtLeast(0) }
        }
        val faster = pill("تسريع الأنيميشن").apply {
            textSize = 14f; setOnClickListener { seek.progress = (seek.progress + 1).coerceAtMost(8) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(slower, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8) })
        row.addView(faster, LinearLayout.LayoutParams(0, WRAP, 1f))

        val blurSwitch = Switch(this).apply {
            text = "تفعيل البلور"; setTextColor(Color.WHITE); textSize = 16f
            isChecked = blurOn; setPadding(0, dp(20), 0, dp(20))
            setOnCheckedChangeListener { _, c -> blurOn = c; prefs.edit().putBoolean("blur", c).apply() }
        }
        val close = pill("تم").apply { setOnClickListener { hideSettings() } }

        card.addView(speedLabel); card.addView(seek); card.addView(row)
        card.addView(blurSwitch); card.addView(close)
        s.addView(card, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        s.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.addView(s)
        settingsView = s
        card.translationY = dp(420).toFloat()
        card.animate().translationY(0f).setDuration(dur(380)).setInterpolator(OvershootInterpolator(0.9f)).start()
    }

    private fun hideSettings() {
        val s = settingsView ?: return
        settingsView = null
        s.animate().alpha(0f).setDuration(dur(220)).withEndAction { root.removeView(s) }.start()
    }

    // ───────────── التطبيقات ─────────────
    private fun loadApps() = thread {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
            .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
            .filter { it.pkg != packageName }
            .sortedBy { it.label.lowercase() }
        runOnUiThread {
            apps = list
            grid.adapter = AppAdapter()
            dock.removeAllViews()
            apps.take(4).forEach { dock.addView(iconView(it, false), LinearLayout.LayoutParams(0, WRAP, 1f)) }
        }
    }

    private fun iconView(app: AppItem, withLabel: Boolean): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        v.addView(ImageView(this).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(dp(56), dp(56)))
        if (withLabel) v.addView(TextView(this).apply {
            text = app.label; setTextColor(Color.WHITE); textSize = 11f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        })
        v.setOnClickListener { launch(app, it) }
        return v
    }

    private fun launch(app: AppItem, view: View) {
        view.animate().scaleX(0.82f).scaleY(0.82f).setDuration(dur(90)).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(dur(220))
                .setInterpolator(OvershootInterpolator(3f)).start()
            packageManager.getLaunchIntentForPackage(app.pkg)?.let {
                startActivity(it)
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }.start()
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(i: Int) = apps[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup) = iconView(apps[i], true)
    }

    // ───────────── درج التطبيقات: بلور + أنيميشن ─────────────
    private fun animateDrawer(open: Boolean) {
        anim?.cancel()
        drawerOpen = open
        if (open) drawer.visibility = View.VISIBLE
        anim = ValueAnimator.ofFloat(if (open) 0f else 1f, if (open) 1f else 0f).apply {
            duration = dur(420)
            interpolator = if (open) OvershootInterpolator(0.8f) else DecelerateInterpolator(1.5f)
            addUpdateListener {
                val f = it.animatedValue as Float
                val c = f.coerceIn(0f, 1f)
                drawer.alpha = c
                drawer.translationY = (1f - f) * dp(160)
                drawer.setBackgroundColor(Color.argb((c * 120).toInt(), 0, 0, 0))
                home.scaleX = 1f - 0.10f * c
                home.scaleY = 1f - 0.10f * c
                home.alpha = 1f - 0.6f * c
                if (Build.VERSION.SDK_INT >= 31) window.setBackgroundBlurRadius(if (blurOn) (c * 90).toInt() else 0)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { if (!open) drawer.visibility = View.GONE }
            })
            start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        hideSettings()
        if (drawerOpen) animateDrawer(false)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (settingsView != null) hideSettings() else if (drawerOpen) animateDrawer(false)
    }
}
