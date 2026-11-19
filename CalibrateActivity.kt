package br.com.telavolume

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Tela para posicionar a área livre exatamente sobre o sensor de digital. */
class CalibrateActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var board: Board

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        hideSystemBars()

        board = Board(this)
        val root = FrameLayout(this)
        root.addView(board, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        panel.addView(TextView(this).apply {
            text = "Arraste o quadrado até ficar sobre o ícone da digital.\n" +
                "Dica: bloqueie a tela, acenda e veja onde o ícone aparece."
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
        })
        val row1 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        row1.addView(btn("− Menor") { board.resize(-dp(8)) })
        row1.addView(btn("+ Maior") { board.resize(dp(8)) })
        row1.addView(btn("Padrão") { prefs.resetFingerprintArea(); board.loadFromPrefs() })
        val row2 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        row2.addView(btn("Visualizar") {
            board.saveToPrefs()
            val s = VolumeKeyService.instance
            if (s == null) toast("Ative o serviço primeiro") else s.previewShield()
        })
        row2.addView(btn("Salvar") {
            board.saveToPrefs()
            VolumeKeyService.instance?.refreshShield()
            toast("Área da digital salva")
            finish()
        })
        panel.addView(row1)
        panel.addView(row2)
        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP).apply { topMargin = dp(48) })

        setContentView(root)
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Área de desenho com o quadrado arrastável. Salva a posição em coordenadas reais da tela. */
    inner class Board(ctx: Context) : View(ctx) {
        private var cx = 0f
        private var cy = 0f
        private var size = 0f
        private var ready = false
        private val loc = IntArray(2)

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5534D399; style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF34D399.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat()
        }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; strokeWidth = dp(1).toFloat()
        }

        init { setBackgroundColor(0xFF0F1419.toInt()) }

        private fun realSize(): Pair<Int, Int> {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            return dm.widthPixels to dm.heightPixels
        }

        fun loadFromPrefs() {
            val (sw, sh) = realSize()
            getLocationOnScreen(loc)
            cx = prefs.fpX * sw - loc[0]
            cy = prefs.fpY * sh - loc[1]
            size = prefs.fpSize * sw
            ready = true
            invalidate()
        }

        fun saveToPrefs() {
            if (!ready) return
            val (sw, sh) = realSize()
            getLocationOnScreen(loc)
            prefs.fpX = ((cx + loc[0]) / sw).coerceIn(0f, 1f)
            prefs.fpY = ((cy + loc[1]) / sh).coerceIn(0f, 1f)
            prefs.fpSize = (size / sw).coerceIn(0.05f, 0.6f)
        }

        fun resize(delta: Int) {
            val (sw, _) = realSize()
            size = (size + delta).coerceIn(dp(40).toFloat(), sw * 0.6f)
            invalidate()
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (!ready) post { loadFromPrefs() }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!ready) return
            val h = size / 2
            canvas.drawRect(cx - h, cy - h, cx + h, cy + h, fill)
            canvas.drawRect(cx - h, cy - h, cx + h, cy + h, stroke)
            canvas.drawLine(cx - h, cy, cx + h, cy, cross)
            canvas.drawLine(cx, cy - h, cx, cy + h, cross)
        }

        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    cx = event.x
                    cy = event.y
                    invalidate()
                }
            }
            return true
        }
    }
}
