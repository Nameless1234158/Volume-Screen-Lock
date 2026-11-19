package br.com.telavolume

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import kotlin.math.abs

class VolumeKeyService : AccessibilityService() {

    companion object {
        /** Espera para diferenciar um clique simples de Volume+ e Volume− juntos (tela acesa). */
        private const val COMBO_WINDOW_MS = 150L
        /** Intervalo máximo entre os dois botões com a tela apagada. */
        private const val OFF_COMBO_WINDOW_MS = 600L
        /** Ignora botões logo após apagar/acender, para não disparar duas vezes. */
        private const val COOLDOWN_MS = 1200L
        private const val PREVIEW_MS = 4000L

        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"

        private const val SYSTEM_UI = "com.android.systemui"
        /** Apps que podem aparecer sobre a tela de bloqueio e precisam de toque. */
        private val LOCKSCREEN_APPS = listOf("incallui", "telecom", "clock", "alarm")

        private var ref: WeakReference<VolumeKeyService>? = null
        val instance: VolumeKeyService? get() = ref?.get()
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private lateinit var keyguard: KeyguardManager
    private lateinit var power: PowerManager

    // ---------- Tela acesa ----------
    private var upHeld = false
    private var downHeld = false
    private var comboFired = false
    private var ignoreUntil = 0L
    private var pendingDirection = 0
    private val pendingSingle = Runnable {
        val dir = pendingDirection
        pendingDirection = 0
        if (dir == 0) return@Runnable
        if (isShieldVisible && !shieldPreview && dir == AudioManager.ADJUST_RAISE) {
            releaseShield()
        } else {
            adjustVolume(dir)
        }
    }

    // ---------- Tela apagada ----------
    private var armed = false
    private var lockedByUs = false
    private var session: MediaSession? = null
    private var silentTrack: AudioTrack? = null
    private var savedMusicVolume = -1
    private var lastUpAt = 0L
    private var lastDownAt = 0L

    // ---------- Escudo ----------
    private val shieldViews = mutableListOf<View>()
    private var shieldPreview = false
    private var shieldReleased = false
    private var appOverLockscreen = false
    private val isShieldVisible get() = shieldViews.isNotEmpty()
    private val syncShieldRunnable = Runnable { syncShield() }
    private var modeListener: AudioManager.OnModeChangedListener? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON -> {
                    disarm()
                    lockedByUs = false
                    resetKeyState()
                    syncShield()
                    // O estado do bloqueio às vezes atualiza um pouco depois de acender
                    handler.removeCallbacks(syncShieldRunnable)
                    handler.postDelayed(syncShieldRunnable, 700)
                }
                Intent.ACTION_USER_PRESENT -> hideShield() // desbloqueado: toque liberado
            }
        }
    }

    /** Plano B: se a One UI mudar o volume em vez de avisar a sessão, detecta pela mudança. */
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!armed) return
            if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
            val value = intent.getIntExtra(EXTRA_VALUE, -1)
            val prev = intent.getIntExtra(EXTRA_PREV, -1)
            if (value < 0 || prev < 0 || value == prev) return
            onScreenOffPress(if (value > prev) 1 else -1, "volume do sistema")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ref = WeakReference(this)
        audio = getSystemService(AudioManager::class.java)
        wm = getSystemService(WindowManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        power = getSystemService(PowerManager::class.java)
        prefs = Prefs(this)

        register(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        register(volumeReceiver, IntentFilter(VOLUME_CHANGED_ACTION))

        if (Build.VERSION.SDK_INT >= 31) {
            val l = AudioManager.OnModeChangedListener { syncShield() }
            audio.addOnModeChangedListener(mainExecutor, l)
            modeListener = l
        }
    }

    private fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onDestroy() {
        disarm()
        hideShield()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(volumeReceiver) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 31) {
            modeListener?.let { try { audio.removeOnModeChangedListener(it) } catch (_: Exception) {} }
        }
        handler.removeCallbacksAndMessages(null)
        ref = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /** Detecta chamada/alarme sobre a tela de bloqueio para liberar o toque. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val lower = pkg.lowercase()
        when {
            LOCKSCREEN_APPS.any { lower.contains(it) } -> appOverLockscreen = true
            pkg == SYSTEM_UI -> appOverLockscreen = false
            else -> return
        }
        if (power.isInteractive) syncShield()
    }

    // =====================================================================
    //  TELA ACESA: Volume+ e Volume− juntos apagam a tela
    // =====================================================================
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!prefs.enabled) return false
        if (inCall()) return false // durante ligação, os botões funcionam normalmente

        val isUp = code == KeyEvent.KEYCODE_VOLUME_UP

        if (SystemClock.uptimeMillis() < ignoreUntil) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (isUp) upHeld = false else downHeld = false
            }
            return true
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val alreadyHeld = if (isUp) upHeld else downHeld
                // Repetição de um botão cujo primeiro toque não vimos (ex.: segurado desde a tela apagada)
                if (event.repeatCount > 0 && !alreadyHeld) return true

                if (isUp) upHeld = true else downHeld = true

                if (upHeld && downHeld) {
                    if (!comboFired) {
                        comboFired = true
                        handler.removeCallbacks(pendingSingle)
                        pendingDirection = 0
                        lockScreenNow()
                    }
                    return true
                }
                if (comboFired) return true

                val dir = if (isUp) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                if (event.repeatCount == 0) {
                    pendingDirection = dir
                    handler.removeCallbacks(pendingSingle)
                    handler.postDelayed(pendingSingle, COMBO_WINDOW_MS)
                } else if (pendingDirection == 0) {
                    adjustVolume(dir) // botão segurado: continua mudando o volume
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (isUp) upHeld = false else downHeld = false
                if (!upHeld && !downHeld) comboFired = false
                return true
            }
        }
        return false
    }

    private fun inCall(): Boolean {
        val mode = audio.mode
        return mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun adjustVolume(direction: Int) {
        audio.adjustSuggestedStreamVolume(
            direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI
        )
    }

    private fun resetKeyState() {
        upHeld = false
        downHeld = false
        comboFired = false
        pendingDirection = 0
        handler.removeCallbacks(pendingSingle)
    }

    /** Apaga e bloqueia a tela exatamente como o botão lateral (mantém a digital funcionando). */
    fun lockScreenNow(): Boolean {
        lockedByUs = true
        ignoreUntil = SystemClock.uptimeMillis() + COOLDOWN_MS
        val ok = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        if (!ok) lockedByUs = false
        return ok
    }

    private fun onScreenOff() {
        resetKeyState()
        shieldReleased = false
        appOverLockscreen = false
        // Escudo já posicionado antes de acender: nenhum toque fantasma passa no instante em que a tela liga
        if (prefs.enabled && prefs.shieldEnabled) showShield(preview = false)
        val musicPlaying = audio.isMusicActive
        if (prefs.enabled && (lockedByUs || (prefs.armOnPowerOff && !musicPlaying))) arm()
    }

    // =====================================================================
    //  TELA APAGADA: escuta os botões pela sessão de mídia
    // =====================================================================
    private fun arm() {
        if (armed) return
        armed = true
        lastUpAt = 0L
        lastDownAt = 0L

        // Prepara o volume de mídia para o plano B (precisa poder subir e descer)
        if (!audio.isMusicActive) {
            try {
                savedMusicVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (savedMusicVolume == 0 || savedMusicVolume == max) {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, 0)
                }
            } catch (_: Exception) {
                savedMusicVolume = -1
            }
        } else {
            savedMusicVolume = -1
        }

        if (prefs.silentAudio) startSilence()
        startSession()
    }

    private fun startSession() {
        try {
            val s = MediaSession(this, "TelaVolume")
            val provider = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction != 0) handler.post {
                        if (armed) onScreenOffPress(direction, "sessão de mídia")
                    }
                }
            }
            s.setPlaybackToRemote(provider)
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .build()
            )
            s.isActive = true
            session = s
        } catch (_: Exception) {
            session = null
        }
    }

    private fun startSilence() {
        try {
            val rate = 8000
            val frames = rate // 1 segundo de silêncio em loop
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build()
            track.write(ShortArray(frames), 0, frames)
            track.setLoopPoints(0, frames, -1)
            track.play()
            silentTrack = track
        } catch (_: Exception) {
            silentTrack = null
        }
    }

    private fun onScreenOffPress(direction: Int, source: String) {
        val now = SystemClock.uptimeMillis()
        if (direction > 0) lastUpAt = now else lastDownAt = now
        if (lastUpAt > 0 && lastDownAt > 0 && abs(lastUpAt - lastDownAt) <= OFF_COMBO_WINDOW_MS) {
            lastUpAt = 0L
            lastDownAt = 0L
            wakeScreen(source)
        }
    }

    private fun disarm() {
        if (!armed) return
        armed = false
        session?.let {
            try { it.isActive = false; it.release() } catch (_: Exception) {}
        }
        session = null
        silentTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        silentTrack = null
        if (savedMusicVolume >= 0) {
            try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0) } catch (_: Exception) {}
        }
        savedMusicVolume = -1
    }

    private fun wakeScreen(source: String) {
        disarm()
        lockedByUs = false
        resetKeyState()
        ignoreUntil = SystemClock.uptimeMillis() + COOLDOWN_MS

        try {
            @Suppress("DEPRECATION")
            val wl = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "TelaVolume:acender"
            )
            wl.acquire(3000L)
        } catch (_: Exception) {}

        try {
            startActivity(
                Intent(this, WakeActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            )
        } catch (_: Exception) {}

        prefs.recordWake(source)
    }

    // =====================================================================
    //  ESCUDO: bloqueia toques na tela de bloqueio, exceto na área da digital
    // =====================================================================
    private fun syncShield() {
        if (shieldPreview) return
        val shouldShow = prefs.enabled && prefs.shieldEnabled && !shieldReleased &&
            !appOverLockscreen && !inCall() && keyguard.isKeyguardLocked
        if (shouldShow && !isShieldVisible) showShield(preview = false)
        else if (!shouldShow && isShieldVisible) hideShield()
    }

    /** Volume+ sozinho com o escudo ativo: libera o toque (para digitar o PIN, por exemplo). */
    private fun releaseShield() {
        shieldReleased = true
        hideShield()
        Toast.makeText(this, "Toque liberado até a tela apagar", Toast.LENGTH_SHORT).show()
    }

    /** Mostra o escudo escurecido por alguns segundos, para conferir a calibração. */
    fun previewShield() {
        showShield(preview = true)
        handler.postDelayed({
            if (shieldPreview) {
                hideShield()
                syncShield()
            }
        }, PREVIEW_MS)
    }

    fun refreshShield() {
        if (!shieldPreview) {
            hideShield()
            if (power.isInteractive) syncShield()
        }
    }

    private fun showShield(preview: Boolean) {
        hideShield()
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val sw = dm.widthPixels
        val sh = dm.heightPixels

        val size = (prefs.fpSize * sw).toInt().coerceIn(dp(40), sw)
        val cx = (prefs.fpX * sw).toInt()
        val cy = (prefs.fpY * sh).toInt()
        val left = (cx - size / 2).coerceIn(0, sw)
        val top = (cy - size / 2).coerceIn(0, sh)
        val right = (left + size).coerceAtMost(sw)
        val bottom = (top + size).coerceAtMost(sh)

        val color = if (preview) 0xAA000000.toInt() else Color.TRANSPARENT
        // Quatro blocos ao redor do sensor: o quadrado do meio fica sem janela, e o toque chega à digital
        addBlock(0, 0, sw, top, color, withLabel = true)
        addBlock(0, bottom, sw, sh - bottom, color, withLabel = false)
        addBlock(0, top, left, bottom - top, color, withLabel = false)
        addBlock(right, top, sw - right, bottom - top, color, withLabel = false)
        shieldPreview = preview
    }

    private fun addBlock(x: Int, y: Int, w: Int, h: Int, color: Int, withLabel: Boolean) {
        if (w <= 0 || h <= 0) return
        val view: View = if (withLabel) {
            FrameLayout(this).apply {
                setBackgroundColor(color)
                addView(
                    TextView(this@VolumeKeyService).apply {
                        text = "🛡 Toques bloqueados · use a digital · Volume+ libera"
                        setTextColor(Color.WHITE)
                        textSize = 12f
                        setPadding(dp(14), dp(6), dp(14), dp(6))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(20).toFloat()
                            setColor(0x99000000.toInt())
                        }
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    ).apply { topMargin = dp(64) }
                )
            }
        } else {
            View(this).apply { setBackgroundColor(color) }
        }
        // Consome o toque sem fazer nada: custo mínimo mesmo com muitos toques fantasmas
        view.setOnTouchListener { _, _ -> true }

        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        }
        try {
            wm.addView(view, lp)
            shieldViews.add(view)
        } catch (_: Exception) {}
    }

    private fun hideShield() {
        for (v in shieldViews) {
            try { wm.removeViewImmediate(v) } catch (_: Exception) {}
        }
        shieldViews.clear()
        shieldPreview = false
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
