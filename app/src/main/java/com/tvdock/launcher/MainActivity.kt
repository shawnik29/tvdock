package com.tvdock.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sin
import kotlin.random.Random

/** Minimal Apple TV-style home: rotating wallpaper + dock of favourite apps. No network access. */
class MainActivity : Activity() {
    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val ROTATE_MS = 5 * 60 * 1000L
        val DEFAULTS = listOf(
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.spotify.tv.android",
            "com.google.android.youtube.tvmusic", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "com.android.vending"
        )
    }

    private lateinit var bg: Array<ImageView>
    private lateinit var dock: LinearLayout
    private lateinit var label: TextView
    private lateinit var prefs: SharedPreferences
    private var front = 0
    private var idx = 0
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val rotate = object : Runnable {
        override fun run() { nextWallpaper(); ui.postDelayed(this, ROTATE_MS) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("dock", MODE_PRIVATE)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        bg = Array(2) { i -> ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = if (i == 0) 1f else 0f } }
        bg.forEach { root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0x99000000.toInt()))
        }, FrameLayout.LayoutParams(MATCH, dp(320), Gravity.BOTTOM))
        root.addView(TextClock(this).apply {
            format12Hour = "h:mm a"; format24Hour = "HH:mm"; textSize = 34f
            setTextColor(Color.WHITE); setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(36), dp(56), 0) })

        label = TextView(this).apply {
            textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
        }
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(22)); clipChildren = false; clipToPadding = false
            background = GradientDrawable().apply { cornerRadius = dp(36).toFloat(); setColor(0x55FFFFFF) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false
            addView(label); addView(dock)
        }
        root.addView(col, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(48) })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        rebuildDock()
        ui.removeCallbacks(rotate); ui.post(rotate)
    }

    override fun onPause() { ui.removeCallbacks(rotate); super.onPause() }
    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); dock.getChildAt(0)?.requestFocus() }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() { /* home screen: ignore Back */ }

    // ---------- dock ----------
    private fun favourites(): List<String> =
        prefs.getString("favs", null)?.split(",")?.filter { it.isNotEmpty() } ?: DEFAULTS

    private fun launchIntent(pkg: String): Intent? =
        packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)

    private fun startSafe(i: Intent) {
        try { startActivity(i) } catch (e: Exception) { Toast.makeText(this, "Can't open that app", Toast.LENGTH_SHORT).show() }
    }

    private fun toggleFav(pkg: String): Boolean {
        val l = favourites().toMutableList()
        val added = !l.remove(pkg); if (added) l.add(pkg)
        prefs.edit().putString("favs", l.joinToString(",")).apply()
        rebuildDock(); return added
    }

    private fun rebuildDock() {
        dock.removeAllViews()
        for (pkg in favourites()) {
            val i = launchIntent(pkg) ?: continue
            val ai = try { packageManager.getApplicationInfo(pkg, 0) } catch (e: Exception) { continue }
            addTile(ai.loadIcon(packageManager), ai.loadLabel(packageManager).toString(), { startSafe(i) }) { toggleFav(pkg); true }
        }
        addTile(BitmapDrawable(resources, glyph(false)), "All apps  (hold OK on an app to pin it)", { showAllApps() }, null)
        addTile(BitmapDrawable(resources, glyph(true)), "Settings", { startSafe(Intent(android.provider.Settings.ACTION_SETTINGS)) }, null)
        dock.getChildAt(0)?.requestFocus()
    }

    private fun addTile(icon: Drawable, name: String, click: () -> Unit, longClick: (() -> Boolean)?) {
        val ring = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setStroke(dp(3), Color.WHITE) }
        val v = ImageView(this).apply {
            setImageDrawable(icon); scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6)); isFocusable = true; isClickable = true
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(92)).apply { setMargins(dp(10), 0, dp(10), 0) }
            setOnClickListener { click() }
            if (longClick != null) setOnLongClickListener { longClick() }
            setOnFocusChangeListener { v, f ->
                v.background = if (f) ring else null
                v.animate().scaleX(if (f) 1.25f else 1f).scaleY(if (f) 1.25f else 1f)
                    .translationY(if (f) -dp(10).toFloat() else 0f).setDuration(120).start()
                if (f) label.text = name
            }
        }
        dock.addView(v)
    }

    private fun glyph(settings: Boolean): Bitmap {
        val s = 256; val bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(bm)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF2C2C2E.toInt(); c.drawRoundRect(0f, 0f, s.toFloat(), s.toFloat(), 56f, 56f, p)
        p.color = Color.WHITE
        if (settings) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 22f; c.drawCircle(128f, 128f, 52f, p); p.style = Paint.Style.FILL
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0)
                c.drawCircle(128f + (88 * Math.cos(a)).toFloat(), 128f + (88 * Math.sin(a)).toFloat(), 13f, p)
            }
        } else for (x in 0..2) for (y in 0..2) c.drawCircle(68f + x * 60f, 68f + y * 60f, 16f, p)
        return bm
    }

    private fun launchables(): List<ResolveInfo> {
        val pm = packageManager
        val a = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
        val b = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        return (a + b).distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
    }

    private fun showAllApps() {
        val apps = launchables(); val pm = packageManager
        lateinit var dlg: AlertDialog
        val grid = GridView(this).apply {
            numColumns = 6; verticalSpacing = dp(12); horizontalSpacing = dp(12); setPadding(dp(16), dp(16), dp(16), dp(16))
            selector = GradientDrawable().apply { setColor(0x44FFFFFF); cornerRadius = dp(16).toFloat() }
            adapter = object : BaseAdapter() {
                override fun getCount() = apps.size
                override fun getItem(p: Int) = apps[p]
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, cv: View?, parent: ViewGroup): View = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(8), dp(4), dp(8))
                    addView(ImageView(context).apply { setImageDrawable(apps[p].loadIcon(pm)) }, LinearLayout.LayoutParams(dp(64), dp(64)))
                    addView(TextView(context).apply {
                        text = apps[p].loadLabel(pm); setTextColor(Color.WHITE); textSize = 14f
                        maxLines = 1; gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }
            }
            setOnItemClickListener { _, _, p, _ ->
                launchIntent(apps[p].activityInfo.packageName)?.let { dlg.dismiss(); startSafe(it) }
            }
            setOnItemLongClickListener { _, _, p, _ ->
                val added = toggleFav(apps[p].activityInfo.packageName)
                Toast.makeText(this@MainActivity, if (added) "Pinned to dock" else "Removed from dock", Toast.LENGTH_SHORT).show(); true
            }
        }
        dlg = AlertDialog.Builder(this).setTitle("All apps - OK opens, hold OK pins/unpins").setView(grid).create()
        dlg.show()
        dlg.window?.setBackgroundDrawable(ColorDrawable(0xEE111111.toInt()))
        dlg.window?.setLayout(dp(980), dp(560))
    }

    // ---------- wallpapers ----------
    private fun wallDir() = File(getExternalFilesDir(null), "wallpapers")

    private fun nextWallpaper() {
        val i = idx++
        io.execute {
            val files = wallDir().listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                ?.sortedBy { it.name } ?: emptyList()
            val bmp = (if (files.isNotEmpty()) decode(files[i % files.size]) else null) ?: scene(i % 4)
            ui.post { crossfade(bmp) }
        }
    }

    private fun crossfade(b: Bitmap) {
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
