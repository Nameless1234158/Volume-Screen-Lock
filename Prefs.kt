package br.com.telavolume

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tela_volume", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Também acender pelo volume quando a tela foi apagada pelo botão lateral. */
    var armOnPowerOff: Boolean
        get() = sp.getBoolean("arm_on_power_off", true)
        set(v) = sp.edit().putBoolean("arm_on_power_off", v).apply()

    /** Toca silêncio enquanto a tela está apagada, para a One UI repassar os botões de volume. */
    var silentAudio: Boolean
        get() = sp.getBoolean("silent_audio", true)
        set(v) = sp.edit().putBoolean("silent_audio", v).apply()

    /** Escudo contra toques na tela de bloqueio (só a área da digital fica livre). */
    var shieldEnabled: Boolean
        get() = sp.getBoolean("shield_enabled", true)
        set(v) = sp.edit().putBoolean("shield_enabled", v).apply()

    /** Centro da área da digital, em fração da largura/altura real da tela. */
    var fpX: Float
        get() = sp.getFloat("fp_x", DEFAULT_FP_X)
        set(v) = sp.edit().putFloat("fp_x", v).apply()

    var fpY: Float
        get() = sp.getFloat("fp_y", DEFAULT_FP_Y)
        set(v) = sp.edit().putFloat("fp_y", v).apply()

    /** Lado do quadrado livre, em fração da largura da tela. */
    var fpSize: Float
        get() = sp.getFloat("fp_size", DEFAULT_FP_SIZE)
        set(v) = sp.edit().putFloat("fp_size", v).apply()

    fun resetFingerprintArea() {
        sp.edit().putFloat("fp_x", DEFAULT_FP_X).putFloat("fp_y", DEFAULT_FP_Y)
            .putFloat("fp_size", DEFAULT_FP_SIZE).apply()
    }

    /** Com a tela apagada, 2 cliques rápidos em qualquer botão de volume também acendem. */
    var anyTwoPresses: Boolean
        get() = sp.getBoolean("any_two_presses", true)
        set(v) = sp.edit().putBoolean("any_two_presses", v).apply()

    // ---------- Diagnóstico ----------
    val diag: String
        get() = sp.getString("diag", "") ?: ""

    fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val lines = diag.lines().filter { it.isNotBlank() }.takeLast(59) + "$ts  $msg"
        sp.edit().putString("diag", lines.joinToString("\n")).apply()
    }

    fun clearDiag() = sp.edit().putString("diag", "").apply()

    val lastWake: String?
        get() = sp.getString("last_wake", null)

    fun recordWake(source: String) {
        val ts = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())
        sp.edit().putString("last_wake", "$ts — via $source").apply()
    }

    companion object {
        // Posição aproximada do sensor no Galaxy S24 FE (ajustável na calibração)
        const val DEFAULT_FP_X = 0.50f
        const val DEFAULT_FP_Y = 0.86f
        const val DEFAULT_FP_SIZE = 0.22f
    }
}
