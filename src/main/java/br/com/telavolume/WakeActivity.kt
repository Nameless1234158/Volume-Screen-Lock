package br.com.telavolume

import android.app.Activity
import android.os.Bundle

/**
 * Tela transparente e instantânea: acende o display por cima da tela de bloqueio
 * e se fecha. O aparelho continua bloqueado, aguardando a digital.
 */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.decorView.postDelayed({ finish() }, 400)
    }
}
