package com.tvdock.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.net.Uri
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.sin
import kotlin.random.Random

/** TV Dock: greeting + clock, pill buttons, big focused-app title, a row of pinned app tiles and a row of all apps. */
class MainActivity : Activity() {
    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val ROTATE_MS = 2 * 60 * 1000L
        const val UA = "TVDock/1.0 (personal Android TV home screen; Wikimedia Commons public-domain art)"
        // Public-domain paintings on Wikimedia Commons: file name to on-screen caption
        val ART = listOf(
            "Autumn--On the Hudson River-1860-Jasper Francis Cropsey.jpg" to "Jasper Cropsey \u00b7 Autumn, On the Hudson River (1860)",
            "Golden Autumn (1895).jpg" to "Isaac Levitan \u00b7 Golden Autumn (1895)",
            "Jasper Francis Cropsey - Autumn on Greenwood Lake - Google Art Project.jpg" to "Jasper Cropsey \u00b7 Autumn on Greenwood Lake",
            "Greenwood Lake Autumn on the Hudson-Jasper Francis Cropsey-1875.jpg" to "Jasper Cropsey \u00b7 Greenwood Lake, Autumn (1875)",
            "Crospey Jasper Francis Autumn Landscape.jpg" to "Jasper Cropsey \u00b7 Autumn Landscape",
            "Jasper Francis Cropsey Autumn in America.jpg" to "Jasper Cropsey \u00b7 Autumn in America"
        )
        val DEFAULTS = listOf(
            "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.google.android.youtube.tv",
            "com.spotify.tv.android", "com.google.android.youtube.tvmusic", "com.disney.disneyplus",
            "ca.bellmedia.crave", "com.android.vending"
        )
    }

    private lateinit var bg: Array<ImageView>
    private lateinit var scroll: ScrollView
    private lateinit var greeting: TextView
    private lateinit var clock: TextView
    private lateinit var dateView: TextView
    private lateinit var title: TextView
    private lateinit var pinnedRow: LinearLayout
    private lateinit var appsRow: LinearLayout
    private lateinit var prefs: SharedPreferences
    private var front = 0
    private var idx = Random.nextInt(1000)
    private lateinit var caption: TextView
    private val net = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val rotate = object : Runnable {
        override fun run() { nextWallpaper(); ui.postDelayed(this, ROTATE_MS) }
    }
    private val tick = object : Runnable {
        override fun run() { updateClock(); ui.postDelayed(this, 15000L) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("dock", MODE_PRIVATE)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        bg = Array(2) { i -> ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = if (i == 0) 1f else 0f } }
        bg.forEach { root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xAA000000.toInt(), 0x33000000, 0x88000000.toInt()))
        }, FrameLayout.LayoutParams(MATCH, MATCH))

        greeting = TextView(this).apply { textSize = 13f; letterSpacing = 0.2f; setTextColor(0xCCFFFFFF.toInt()) }
        clock = TextView(this).apply { textSize = 52f; setTextColor(Color.WHITE); includeFontPadding = false }
        dateView = TextView(this).apply { textSize = 13f; letterSpacing = 0.2f; setTextColor(0xCCFFFFFF.toInt()) }
        val divider = View(this).apply { setBackgroundColor(0x66FFFFFF) }
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(clock)
            addView(divider, LinearLayout.LayoutParams(dp(1), dp(34)).apply { setMargins(dp(16), 0, dp(16), 0) })
            addView(dateView)
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(greeting); addView(timeRow) }
        val pills = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill("Inputs") { showInputs() })
            addView(pill("Wi-Fi") { startSafe(Intent(Settings.ACTION_WIFI_SETTINGS)) })
            addView(pill("Settings") { startSafe(Intent(Settings.ACTION_SETTINGS)) })
        }
        caption = TextView(this).apply { textSize = 12f; gravity = Gravity.END; setTextColor(0xB3FFFFFF.toInt()); setShadowLayer(6f, 0f, 1f, Color.BLACK) }
        val header = FrameLayout(this).apply {
            addView(caption, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.TOP).apply { topMargin = dp(52) })
            addView(left, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.TOP))
            addView(pills, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.TOP))
        }

        title = TextView(this).apply {
            textSize = 30f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        pinnedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        appsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        fun hscroll(row: LinearLayout) = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            setPadding(dp(48), dp(18), dp(48), dp(18))
            addView(row, FrameLayout.LayoutParams(WRAP, WRAP))
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false
            setPadding(0, dp(36), 0, dp(140))
            addView(header, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(48), 0, dp(48), 0) })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(MATCH, dp(110)))
            addView(title, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(48) })
            addView(hscroll(pinnedRow), LinearLayout.LayoutParams(MATCH, WRAP))
            addView(hscroll(appsRow), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        fetchArt()
    }

    override fun onResume() {
        super.onResume()
        rebuild(); updateClock()
        ui.removeCallbacks(tick); ui.post(tick)
        ui.removeCallbacks(rotate); ui.post(rotate)
    }

    override fun onPause() { ui.removeCallbacks(rotate); ui.removeCallbacks(tick); super.onPause() }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i); scroll.smoothScrollTo(0, 0); pinnedRow.getChildAt(0)?.requestFocus()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { /* home screen: ignore Back */ }

    // ---------- header ----------
    private fun updateClock() {
        val now = Date()
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        greeting.text = if (h < 12) "GOOD MORNING" else if (h < 17) "GOOD AFTERNOON" else "GOOD EVENING"
        val is24 = DateFormat.is24HourFormat(this)
        val s = SpannableStringBuilder(SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(now))
        if (!is24) {
            val start = s.length
            s.append(" " + SimpleDateFormat("a", Locale.getDefault()).format(now).uppercase())
            s.setSpan(RelativeSizeSpan(0.35f), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        clock.text = s
        dateView.text = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now).uppercase()
    }

    private fun pill(name: String, click: () -> Unit): TextView {
        val normal = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0x66000000) }
        val focused = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xF2FFFFFF.toInt()) }
        return TextView(this).apply {
            this.text = name; textSize = 14f; setTextColor(Color.WHITE)
            setPadding(dp(18), dp(9), dp(18), dp(9)); background = normal
            isFocusable = true; isClickable = true
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) }
            setOnClickListener { click() }
            setOnFocusChangeListener { v, f ->
                v.background = if (f) focused else normal
                setTextColor(if (f) 0xFF111111.toInt() else Color.WHITE)
            }
        }
    }

    private fun showInputs() {
        try {
            val tm = getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
            val inputs = tm.tvInputList.filter { it.isPassthroughInput }
            if (inputs.isEmpty()) { Toast.makeText(this, "No inputs found", Toast.LENGTH_SHORT).show(); return }
            val names = inputs.map { it.loadLabel(this).toString() }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Inputs").setItems(names) { _, w ->
                startSafe(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputs[w].id)))
            }.show()
        } catch (e: Exception) {
            Toast.makeText(this, "Can't list inputs on this TV", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- tiles ----------
    private fun favourites(): List<String> =
        prefs.getString("favs", null)?.split(",")?.filter { it.isNotEmpty() } ?: DEFAULTS

    private fun launchIntent(pkg: String): Intent? =
        packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)

    private fun startSafe(i: Intent) {
        try { startActivity(i) } catch (e: Exception) { Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show() }
    }

    private fun toggleFav(pkg: String) {
        val l = favourites().toMutableList()
        if (!l.remove(pkg)) l.add(pkg)
        prefs.edit().putString("favs", l.joinToString(",")).apply()
        rebuild()
    }

    private fun launchables(): List<ResolveInfo> {
        val pm = packageManager
        val a = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
        val b = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        return (a + b).distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
    }

    private fun rebuild() {
        pinnedRow.removeAllViews(); appsRow.removeAllViews()
        val favs = favourites()
        val all = launchables()
        val byPkg = all.associateBy { it.activityInfo.packageName }
        for (pkg in favs) { val ri = byPkg[pkg] ?: continue; pinnedRow.addView(tileFor(ri)) }
        for (ri in all) if (ri.activityInfo.packageName !in favs) appsRow.addView(tileFor(ri))
        pinnedRow.getChildAt(0)?.requestFocus()
    }

    private fun bannerFor(ri: ResolveInfo): Drawable? {
        val pm = packageManager
        var d: Drawable? = null
        try { d = pm.getActivityBanner(ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)) } catch (e: Exception) { }
        if (d == null) d = ri.activityInfo.applicationInfo.loadBanner(pm)
        return d
    }

    private fun tileFor(ri: ResolveInfo): View {
        val pkg = ri.activityInfo.packageName
        return makeTile(ri.loadLabel(packageManager).toString(), bannerFor(ri), ri.loadIcon(packageManager),
            { launchIntent(pkg)?.let { startSafe(it) } }, { toggleFav(pkg) })
    }

    private fun makeTile(label: String, banner: Drawable?, icon: Drawable, onClick: () -> Unit, onLong: () -> Unit): View {
        val ring = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setStroke(dp(3), Color.WHITE); setColor(Color.TRANSPARENT) }
        val tile = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFF2A2E38.toInt()) }
            clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
            isFocusable = true; isClickable = true
            layoutParams = LinearLayout.LayoutParams(dp(196), dp(110)).apply { setMargins(dp(7), 0, dp(7), 0) }
        }
        if (banner != null) {
            tile.addView(ImageView(this).apply { setImageDrawable(banner); scaleType = ImageView.ScaleType.CENTER_CROP },
                FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            tile.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(ImageView(this@MainActivity).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(52), dp(52)))
                addView(TextView(this@MainActivity).apply {
                    text = label; setTextColor(Color.WHITE); textSize = 13f; maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), 0)
                })
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        val overlay = View(this).apply { background = ring; alpha = 0f }
        tile.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        tile.setOnClickListener { onClick() }
        tile.setOnLongClickListener { onLong(); true }
        tile.setOnFocusChangeListener { v, f ->
            overlay.alpha = if (f) 1f else 0f
            v.animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).setDuration(120).start()
            if (f) title.text = label
        }
        return tile
    }

    // ---------- wallpapers ----------
    private fun wallDir() = File(getExternalFilesDir(null), "wallpapers")

    private fun artFile(i: Int) = File(File(filesDir, "art").apply { mkdirs() }, "art_$i.jpg")

    /** One-time download of the paintings into private app storage; skipped when already cached. */
    private fun fetchArt() {
        net.execute {
            var got = false
            ART.forEachIndexed { i, _ ->
                val f = artFile(i)
                if (!f.exists() || f.length() < 50000) { if (download(ART[i].first, f)) got = true }
            }
            if (got) ui.post { nextWallpaper() }
        }
    }

    private fun download(name: String, dest: File): Boolean {
        val enc = Uri.encode(name)
        for (suffix in listOf("?width=1920", "")) {
            try {
                val c = URL("https://commons.wikimedia.org/wiki/Special:FilePath/" + enc + suffix).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", UA); c.connectTimeout = 15000; c.readTimeout = 30000
                if (c.responseCode != 200 || (suffix.isEmpty() && c.contentLength > 15000000)) { c.disconnect(); continue }
                val tmp = File(dest.path + ".part")
                c.inputStream.use { inp -> tmp.outputStream().use { out -> inp.copyTo(out) } }
                c.disconnect()
                if (tmp.length() > 50000) return tmp.renameTo(dest)
                tmp.delete()
            } catch (e: Exception) { }
        }
        return false
    }

    private fun nextWallpaper() {
        val i = idx++
        io.execute {
            val items = ArrayList<Pair<File, String>>()
            ART.forEachIndexed { k, art -> val f = artFile(k); if (f.exists() && f.length() > 50000) items.add(f to art.second) }
            wallDir().listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                ?.sortedBy { it.name }?.forEach { items.add(it to "") }
            val picked = if (items.isNotEmpty()) items[i % items.size] else null
            val art = picked?.let { decode(it.first) }
            val bmp = art ?: scene(i % 4)
            val cap = if (art != null && picked != null) picked.second else ""
            ui.post { crossfade(bmp, cap) }
        }
    }

    private fun crossfade(b: Bitmap, cap: String) {
        caption.text = cap
        val back = bg[1 - front]; back.setImageBitmap(b)
        back.animate().alpha(1f).setDuration(1500).start()
        bg[front].animate().alpha(0f).setDuration(1500).start()
        front = 1 - front
    }

    private fun decode(f: File): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= 1920 && o.outHeight / (s * 2) >= 1080) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (e: Throwable) { null }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt()
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    /** Offline fallback: layered mountain scenes painted in code. */
    private fun scene(n: Int): Bitmap {
        val w = 1280; val h = 720
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(bm); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pal = arrayOf(
            intArrayOf(0xFF1B2A49.toInt(), 0xFFE9806E.toInt(), 0xFFF6C177.toInt()),
            intArrayOf(0xFF0B3D2E.toInt(), 0xFF2F7F5F.toInt(), 0xFFBFE3C0.toInt()),
            intArrayOf(0xFF03254C.toInt(), 0xFF1D6FA3.toInt(), 0xFF9AD4E8.toInt()),
            intArrayOf(0xFF140B33.toInt(), 0xFF5B3C88.toInt(), 0xFFE0A3C2.toInt())
        )[n]
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.8f, pal[0], pal[2], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        p.color = 0xCCFFF3D6.toInt(); c.drawCircle(w * (0.25f + 0.15f * n), h * 0.42f, 70f, p)
        val r = Random(n * 97 + 3)
        for (layer in 0 until 4) {
            val base = h * (0.50f + 0.12f * layer); val amp = 110f - layer * 18f
            val f1 = 0.004f + r.nextFloat() * 0.004f; val ph = r.nextFloat() * 6f
            val path = Path().apply {
                moveTo(0f, h.toFloat()); var x = 0f
                while (x <= w) { lineTo(x, base - amp * (0.5f + 0.5f * sin(x * f1 + ph)) - 20f * sin(x * f1 * 3.1f + ph * 2f)); x += 8f }
                lineTo(w.toFloat(), h.toFloat()); close()
            }
            p.color = mix(pal[1], pal[0], layer / 3.2f); c.drawPath(path, p)
        }
        return bm
    }
}
