package br.com.telavolume

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var serviceStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var lastWakeText: TextView
    private lateinit var diagText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }

        heading("Tela Volume")
        text("Volume+ e Volume− juntos (rápido, sem segurar) apagam a tela. Com a tela apagada, o mesmo clique acende.")

        section("1. Ativar o serviço")
        serviceStatus = text("")
        button("Abrir Acessibilidade") { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        text("Em Acessibilidade › Apps instalados › Tela Volume, ative o serviço. Se aparecer \"Configuração restrita\", toque no botão abaixo, depois em ⋮ (canto superior direito) › Permitir configurações restritas, e volte para ativar.")
        button("Abrir informações do app") {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }

        section("2. Bateria")
        batteryStatus = text("")
        button("Liberar bateria sem restrição") {
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        text("Confirme também em Configurações › Aplicativos › Tela Volume › Bateria › Irrestrito.")

        section("3. Sensor de toque em repouso")
        text("Para o sensor não processar toques com a tela apagada, desligue:\n" +
            "• Tela de bloqueio e AOD › Always On Display\n" +
            "• Segurança e privacidade › Biometria › Impressões digitais › Impressão digital sempre ativa\n" +
            "• Recursos avançados › Movimentos e gestos › Toque duplo para ligar a tela\n\n" +
            "A digital continua funcionando logo depois de acender a tela. Os nomes podem variar conforme a versão da One UI.")
        button("Abrir Configurações") { open(Intent(Settings.ACTION_SETTINGS)) }

        section("4. Escudo na tela de bloqueio")
        text("Com a tela acesa e bloqueada, todos os toques são ignorados, exceto na área da digital. Ao desbloquear, o toque volta ao normal.\n\n" +
            "• Precisa digitar o PIN? Aperte Volume+ uma vez: o toque é liberado até a tela apagar.\n" +
            "• Chamadas e alarmes liberam o toque sozinhos.")
        switch("Escudo ativado", prefs.shieldEnabled) {
            prefs.shieldEnabled = it
            VolumeKeyService.instance?.refreshShield()
        }
        button("Calibrar área da digital") { open(Intent(this, CalibrateActivity::class.java)) }

        section("5. Opções")
        switch("Atalho de volume ativado", prefs.enabled) {
            prefs.enabled = it
            VolumeKeyService.instance?.refreshShield()
        }
        switch("Acender pelo volume também quando apagar pelo botão lateral", prefs.armOnPowerOff) {
            prefs.armOnPowerOff = it
        }
        switch("Áudio silencioso de reforço (recomendado na Samsung)", prefs.silentAudio) {
            prefs.silentAudio = it
        }
        switch("Com a tela apagada, 2 cliques rápidos em qualquer botão de volume também acendem", prefs.anyTwoPresses) {
            prefs.anyTwoPresses = it
        }

        section("6. Testar")
        text("Toque em \"Apagar a tela agora\", espere 3 segundos e aperte Volume+ e Volume− juntos.")
        button("Apagar a tela agora") {
            val s = VolumeKeyService.instance
            if (s == null) toast("Ative o serviço primeiro (passo 1)") else s.lockScreenNow()
        }
        lastWakeText = text("")

        section("7. Diagnóstico")
        text("Depois de um teste, tire um print desta área e envie para análise.")
        button("Atualizar diagnóstico") { diagText.text = prefs.diag.ifBlank { "(vazio)" } }
        button("Limpar diagnóstico") { prefs.clearDiag(); diagText.text = "(vazio)" }
        diagText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(diagText)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        serviceStatus.text = if (serviceEnabled()) "✅ Serviço ativo" else "❌ Serviço desativado"
        val pm = getSystemService(PowerManager::class.java)
        batteryStatus.text = if (pm.isIgnoringBatteryOptimizations(packageName))
            "✅ Bateria sem restrição" else "⚠️ Bateria com otimização (o sistema pode encerrar o app)"
        lastWakeText.text = "Último acendimento pelo volume: " + (prefs.lastWake ?: "nenhum ainda")
        diagText.text = prefs.diag.ifBlank { "(vazio)" }
    }

    private fun serviceEnabled(): Boolean {
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, VolumeKeyService::class.java)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    // ---------- Componentes simples ----------
    private fun heading(t: String) = root.addView(TextView(this).apply {
        text = t; textSize = 26f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    })

    private fun section(t: String) = root.addView(TextView(this).apply {
        text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(6))
    })

    private fun text(t: String): TextView {
        val tv = TextView(this).apply { text = t; textSize = 15f; setPadding(0, dp(4), 0, dp(4)) }
        root.addView(tv)
        return tv
    }

    private fun button(label: String, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    })

    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        root.addView(Switch(this).apply {
            text = label; textSize = 15f; isChecked = checked
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })

    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { toast("Não foi possível abrir esta tela") }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
