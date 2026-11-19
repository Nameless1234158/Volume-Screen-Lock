package br.com.telavolume

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var pal: Palette
    private var scroll: ScrollView? = null
    private var showingReport = false
    private var setupExpanded = false

    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        pal = Palette(isDark())
        actionBar?.hide()
        styleSystemBars()
        showMain()
    }

    override fun onResume() {
        super.onResume()
        if (showingReport) showReport(keepScroll = true) else showMain(keepScroll = true)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (showingReport) showMain() else super.onBackPressed()
    }

    // =====================================================================
    //  TELA PRINCIPAL
    // =====================================================================
    private fun showMain(keepScroll: Boolean = false) {
        val sameScreen = !showingReport
        showingReport = false
        val p = page()
        p.addView(header())
        p.addView(statusCard())

        p.addView(label("Como usar"))
        p.addView(usageCard())

        p.addView(label("Configuração inicial"))
        p.addView(setupCard())

        p.addView(label("Escudo de toque"))
        p.addView(shieldCard())

        p.addView(label("Comportamento"))
        p.addView(behaviorCard())

        p.addView(label("Teste"))
        p.addView(testCard())

        p.addView(label("Avançado"))
        p.addView(advancedCard())

        p.addView(footer())
        setPage(p, keepScroll && sameScreen)
    }

    private fun header(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(16), dp(4), dp(20))
        }
        val logo = ImageView(this).apply {
            background = GradientDrawable().apply { setColor(pal.accent); cornerRadius = dp(14).toFloat() }
            setImageDrawable(IconDrawable(Ic.POWER, Color.WHITE))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        row.addView(logo, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text("Tela Volume", 22f, pal.text, bold = true))
        texts.addView(text("Controle da tela pelos botões de volume", 13f, pal.sub))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun statusCard(): View {
        val svc = serviceEnabled()
        val bat = batteryOk()
        val ready = svc && bat
        val tint = if (ready) pal.success else pal.warning

        val c = card()
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(16))
        }
        hero.addView(iconCircle(if (ready) Ic.CHECK else Ic.ALERT, tint, 9),
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(if (ready) "Pronto para uso" else "Configuração pendente", 17f, pal.text, bold = true))
        texts.addView(text(
            when {
                ready -> "O atalho de volume está ativo."
                !svc -> "Ative o serviço de acessibilidade para começar."
                else -> "Libere o uso de bateria para o sistema não encerrar o app."
            }, 13f, pal.sub
        ))
        hero.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        c.addView(hero)
        c.addView(divider(18))

        c.addView(infoRow("Serviço de acessibilidade", if (svc) "Ativo" else "Inativo",
            if (svc) pal.success else pal.danger))
        c.addView(infoRow("Uso de bateria", if (bat) "Sem restrição" else "Otimizado",
            if (bat) pal.success else pal.warning))
        val last = prefs.lastWake?.substringBefore(" — via ")
        c.addView(infoRow("Último acendimento", last ?: "Sem registro", if (last != null) pal.text else pal.sub))
        c.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        return c
    }

    private fun usageCard(): View {
        val c = card()
        rows(c,
            row(Ic.POWER, pal.accent, "Apagar a tela", "Pressione Volume+ e Volume− juntos"),
            row(Ic.PHONE, pal.accent, "Acender a tela",
                if (prefs.anyTwoPresses) "Mesmo atalho com a tela apagada, ou dois cliques em um botão de volume"
                else "Mesmo atalho com a tela apagada"),
            row(Ic.LOCK, pal.accent, "Liberar o toque", "Volume+ na tela de bloqueio, para digitar o PIN")
        )
        return c
    }

    private fun setupCard(): View {
        val svc = serviceEnabled()
        val bat = batteryOk()
        val c = card()
        if (svc && bat && !setupExpanded) {
            c.addView(row(Ic.CHECK, pal.success, "Configuração concluída", "Toque para revisar as etapas",
                chevron()) { setupExpanded = true; showMain(keepScroll = true) })
            return c
        }
        rows(c,
            step(svc, "Ativar serviço de acessibilidade",
                "Acessibilidade › Apps instalados › Tela Volume") {
                open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            step(svc, "Permitir configurações restritas",
                "Somente se a ativação for bloqueada: Informações do app › menu › Permitir configurações restritas") {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            },
            step(bat, "Liberar uso de bateria", "Impede que o sistema encerre o app em segundo plano") {
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            },
            step(null, "Ajustar recursos da tela",
                "Desligue Always On Display, Impressão digital sempre ativa e Toque duplo para ligar a tela") {
                open(Intent(Settings.ACTION_SETTINGS))
            }
        )
        if (svc && bat) {
            c.addView(divider(16))
            c.addView(textButton("Ocultar etapas", pal.accent) { setupExpanded = false; showMain(keepScroll = true) })
        }
        return c
    }

    private fun shieldCard(): View {
        val c = card()
        rows(c,
            switchRow(Ic.SHIELD, "Escudo na tela de bloqueio",
                "Ignora toques fora da área da digital até o desbloqueio", prefs.shieldEnabled) {
                prefs.shieldEnabled = it
                VolumeKeyService.instance?.refreshShield()
            },
            row(Ic.FINGERPRINT, pal.accent, "Calibrar área da digital",
                "Posicione a área livre sobre o sensor", chevron()) {
                open(Intent(this, CalibrateActivity::class.java))
            },
            row(Ic.EYE, pal.accent, "Visualizar escudo", "Mostra a área livre por 4 segundos", chevron()) {
                val s = VolumeKeyService.instance
                if (s == null) toast("Ative o serviço de acessibilidade primeiro") else s.previewShield()
            }
        )
        return c
    }

    private fun behaviorCard(): View {
        val c = card()
        rows(c,
            switchRow(Ic.VOLUME, "Atalho de volume",
                "Volume+ e Volume− juntos apagam e acendem a tela", prefs.enabled) {
                prefs.enabled = it
                VolumeKeyService.instance?.refreshShield()
            },
            switchRow(Ic.CLOCK, "Acender após desligamento automático",
                "Escuta o volume também quando a tela apaga pelo tempo limite", prefs.armOnPowerOff) {
                prefs.armOnPowerOff = it
            },
            switchRow(Ic.BOLT, "Dois cliques acendem a tela",
                "Com a tela apagada, dois cliques rápidos em qualquer botão de volume", prefs.anyTwoPresses) {
                prefs.anyTwoPresses = it
            }
        )
        return c
    }

    private fun testCard(): View {
        val c = card()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(text("Apaga a tela imediatamente. Para acender, use o atalho de volume.", 14f, pal.sub))
        box.addView(primaryButton("Apagar a tela agora", Ic.POWER) {
            val s = VolumeKeyService.instance
            if (s == null) toast("Ative o serviço de acessibilidade primeiro") else s.lockScreenNow()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) })
        c.addView(box)
        return c
    }

    private fun advancedCard(): View {
        val c = card()
        val count = prefs.diag.lines().count { it.isNotBlank() }
        rows(c,
            switchRow(Ic.TUNE, "Áudio silencioso de reforço",
                "Use apenas se o acendimento falhar após longos períodos. Aumenta o consumo de bateria.",
                prefs.silentAudio) { prefs.silentAudio = it },
            row(Ic.DOCUMENT, pal.accent, "Relatório de diagnóstico",
                if (count == 0) "Nenhum evento registrado" else "$count eventos registrados", chevron()) {
                showReport()
            }
        )
        return c
    }

    private fun footer(): View = text("Tela Volume · versão ${version()}", 12f, pal.sub).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(28), 0, dp(8))
    }

    // =====================================================================
    //  RELATÓRIO DE DIAGNÓSTICO
    // =====================================================================
    private fun showReport(keepScroll: Boolean = false) {
        val sameScreen = showingReport
        showingReport = true
        val p = page()

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(12))
        }
        val back = ImageView(this).apply {
            setImageDrawable(IconDrawable(Ic.BACK, pal.text))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = ripple(dp(22).toFloat())
            contentDescription = "Voltar"
            setOnClickListener { showMain() }
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
        bar.addView(text("Relatório de diagnóstico", 20f, pal.text, bold = true))
        p.addView(bar)

        p.addView(text("Registra cada vez que a tela apaga e acende e por onde os botões de volume chegaram. " +
            "Se o app não se comportar como esperado, compartilhe este relatório.", 14f, pal.sub).apply {
            setPadding(dp(4), 0, dp(4), 0)
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        actions.addView(chip("Atualizar", Ic.REFRESH) { showReport(keepScroll = true) }, chipLp(0))
        actions.addView(chip("Copiar", Ic.COPY) { copyReport() }, chipLp(8))
        actions.addView(chip("Compartilhar", Ic.SHARE) { shareReport() }, chipLp(8))
        p.addView(actions)

        p.addView(label("Eventos · mais recentes primeiro"))
        val c = card()
        val lines = prefs.diag.lines().filter { it.isNotBlank() }.reversed()
        if (lines.isEmpty()) {
            c.addView(text("Nenhum evento registrado.", 14f, pal.sub).apply {
                setPadding(dp(16), dp(20), dp(16), dp(20))
            })
        } else {
            lines.forEachIndexed { i, line ->
                if (i > 0) c.addView(divider(16))
                c.addView(logRow(line))
            }
        }
        p.addView(c)

        p.addView(textButton("Limpar relatório", pal.danger) {
            AlertDialog.Builder(this)
                .setTitle("Limpar relatório?")
                .setMessage("Todos os eventos registrados serão apagados.")
                .setPositiveButton("Limpar") { _, _ -> prefs.clearDiag(); showReport() }
                .setNegativeButton("Cancelar", null)
                .show()
        }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12) })

        setPage(p, keepScroll && sameScreen)
    }

    private fun logRow(line: String): View {
        val time = line.substringBefore("  ").trim()
        val msg = line.substringAfter("  ", line).trim()
        val color = when {
            msg.startsWith("ACENDENDO") -> pal.success
            msg.contains("FALHOU") -> pal.danger
            else -> pal.text
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(11), dp(16), dp(11))
        }
        row.addView(text(time, 12f, pal.sub).apply { typeface = Typeface.MONOSPACE },
            LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(text(msg, 13f, color, bold = color != pal.text),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun reportText(): String {
        val header = "Tela Volume ${version()} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        val body = prefs.diag.ifBlank { "(nenhum evento registrado)" }
        return "$header\n\n$body"
    }

    private fun copyReport() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Relatório Tela Volume", reportText()))
        if (Build.VERSION.SDK_INT < 33) toast("Relatório copiado")
    }

    private fun shareReport() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Relatório Tela Volume")
            putExtra(Intent.EXTRA_TEXT, reportText())
        }
        open(Intent.createChooser(send, "Compartilhar relatório"))
    }

    // =====================================================================
    //  COMPONENTES
    // =====================================================================
    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(32))
    }

    private fun setPage(content: LinearLayout, keepScroll: Boolean) {
        val y = if (keepScroll) scroll?.scrollY ?: 0 else 0
        val sv = ScrollView(this).apply {
            setBackgroundColor(pal.bg)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        setContentView(sv)
        scroll = sv
        if (y > 0) sv.post { sv.scrollTo(0, y) }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(pal.card)
            cornerRadius = dp(18).toFloat()
            if (!pal.dark) setStroke(dp(1), pal.divider)
        }
        clipToOutline = true
    }

    private fun label(t: String) = text(t.uppercase(), 12f, pal.sub, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(6), dp(24), dp(6), dp(8))
    }

    private fun text(t: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = medium
        setLineSpacing(0f, 1.15f)
    }

    private fun divider(startDp: Int) = View(this).apply {
        setBackgroundColor(pal.divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = dp(startDp)
        }
    }

    private fun rows(c: LinearLayout, vararg views: View) {
        views.forEachIndexed { i, v ->
            if (i > 0) c.addView(divider(70))
            c.addView(v)
        }
    }

    private fun row(
        icon: Ic?, tint: Int, title: String, subtitle: String?,
        trailing: View? = null, onClick: (() -> Unit)? = null
    ): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        if (icon != null) {
            r.addView(iconCircle(icon, tint, 9), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 15f, pal.text, bold = true))
        if (subtitle != null) texts.addView(text(subtitle, 13f, pal.sub).apply { setPadding(0, dp(2), 0, 0) })
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) {
            r.addView(trailing, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) })
        }
        if (onClick != null) {
            r.isClickable = true
            r.background = ripple(0f)
            r.setOnClickListener { onClick() }
        }
        return r
    }

    private fun step(done: Boolean?, title: String, subtitle: String, onClick: () -> Unit): View {
        val (icon, tint) = when (done) {
            true -> Ic.CHECK to pal.success
            false -> Ic.ALERT to pal.warning
            null -> Ic.TUNE to pal.accent
        }
        return row(icon, tint, title, subtitle, chevron(), onClick)
    }

    private fun switchRow(icon: Ic, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val sw = Switch(this).apply {
            isChecked = checked
            showText = false
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, if (pal.dark) 0xFFC4C7CC.toInt() else Color.WHITE))
            trackTintList = ColorStateList(states, intArrayOf(pal.accent, if (pal.dark) 0xFF3A3F47.toInt() else 0xFFCBD0D8.toInt()))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        return row(icon, pal.accent, title, subtitle, sw) { sw.toggle() }
    }

    private fun infoRow(label: String, value: String, valueColor: Int): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(4))
        }
        r.addView(text(label, 14f, pal.sub), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(text(value, 14f, valueColor, bold = true))
        return r
    }

    private fun iconCircle(icon: Ic, tint: Int, padDp: Int) = ImageView(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(withAlpha(tint, 0x24))
        }
        setImageDrawable(IconDrawable(icon, tint))
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    }

    private fun chevron() = ImageView(this).apply {
        setImageDrawable(IconDrawable(Ic.CHEVRON, pal.sub))
        layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
        minimumWidth = dp(20)
        minimumHeight = dp(20)
    }

    private fun primaryButton(label: String, icon: Ic, onClick: () -> Unit): View {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ripple(dp(14).toFloat(), pal.accent, light = true)
            isClickable = true
            setOnClickListener { onClick() }
        }
        b.addView(ImageView(this).apply { setImageDrawable(IconDrawable(icon, Color.WHITE)) },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        b.addView(text(label, 15f, Color.WHITE, bold = true))
        return b
    }

    private fun chip(label: String, icon: Ic, onClick: () -> Unit): View {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            background = ripple(dp(12).toFloat(), pal.card, stroke = pal.divider)
            isClickable = true
            setOnClickListener { onClick() }
        }
        b.addView(ImageView(this).apply { setImageDrawable(IconDrawable(icon, pal.accent)) },
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })
        b.addView(text(label, 13f, pal.accent, bold = true).apply { maxLines = 1 })
        return b
    }

    private fun chipLp(startDp: Int) = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(startDp) }

    private fun textButton(label: String, color: Int, onClick: () -> Unit) = text(label, 15f, color, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = ripple(dp(14).toFloat())
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun ripple(radius: Float, base: Int? = null, light: Boolean = false, stroke: Int? = null): Drawable {
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radius }
        val content = base?.let { color ->
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = radius
                if (stroke != null) setStroke(dp(1), stroke)
            }
        }
        val rippleColor = if (light) 0x33FFFFFF else pal.ripple
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    // =====================================================================
    //  AUXILIARES
    // =====================================================================
    private fun isDark() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    @Suppress("DEPRECATION")
    private fun styleSystemBars() {
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.bg
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (pal.dark) 0 else mask, mask)
        } else if (!pal.dark) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun serviceEnabled(): Boolean {
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, VolumeKeyService::class.java)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun batteryOk(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @Suppress("DEPRECATION")
    private fun version(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }

    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { toast("Não foi possível abrir esta tela") }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)

/** Cores do app, com versões para tema claro e escuro. */
private class Palette(val dark: Boolean) {
    val bg = if (dark) 0xFF0E1014.toInt() else 0xFFF3F4F6.toInt()
    val card = if (dark) 0xFF181B21.toInt() else 0xFFFFFFFF.toInt()
    val text = if (dark) 0xFFE8EAED.toInt() else 0xFF111827.toInt()
    val sub = if (dark) 0xFF9AA0A6.toInt() else 0xFF6B7280.toInt()
    val divider = if (dark) 0xFF262A31.toInt() else 0xFFE5E7EB.toInt()
    val accent = if (dark) 0xFF5B8CFF.toInt() else 0xFF2563EB.toInt()
    val success = if (dark) 0xFF3FB97A.toInt() else 0xFF15803D.toInt()
    val warning = if (dark) 0xFFE0A93B.toInt() else 0xFFB45309.toInt()
    val danger = if (dark) 0xFFF26D6D.toInt() else 0xFFDC2626.toInt()
    val ripple = if (dark) 0x22FFFFFF else 0x14000000
}

/** Conjunto de ícones do app, desenhados em vetor (grade de 24 x 24). */
private enum class Ic {
    POWER, CHECK, ALERT, CHEVRON, BACK, SHIELD, FINGERPRINT, VOLUME, CLOCK,
    DOCUMENT, TUNE, EYE, LOCK, PHONE, BOLT, REFRESH, COPY, SHARE
}

private class IconDrawable(private val ic: Ic, color: Int) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }
    private val path = Path()
    private val rect = RectF()

    override fun draw(c: Canvas) {
        val bw = bounds.width().toFloat()
        val bh = bounds.height().toFloat()
        val size = minOf(bw, bh)
        if (size <= 0f) return
        c.save()
        c.translate(bounds.left + (bw - size) / 2f, bounds.top + (bh - size) / 2f)
        c.scale(size / 24f, size / 24f)
        when (ic) {
            Ic.POWER -> {
                arc(c, 5f, 5f, 19f, 19f, -60f, 300f)
                line(c, 12f, 3f, 12f, 11f)
            }
            Ic.CHECK -> poly(c, 5f, 12.5f, 10f, 17.5f, 19f, 7.5f)
            Ic.ALERT -> {
                line(c, 12f, 6.5f, 12f, 13.5f)
                c.drawCircle(12f, 17.5f, 1.3f, fill)
            }
            Ic.CHEVRON -> poly(c, 9f, 5.5f, 15.5f, 12f, 9f, 18.5f)
            Ic.BACK -> {
                line(c, 20f, 12f, 4.5f, 12f)
                poly(c, 11f, 5.5f, 4.5f, 12f, 11f, 18.5f)
            }
            Ic.SHIELD -> {
                path.reset()
                path.moveTo(12f, 2.5f)
                path.lineTo(19.5f, 5.5f)
                path.lineTo(19.5f, 11f)
                path.cubicTo(19.5f, 15.8f, 16.3f, 19.6f, 12f, 21.5f)
                path.cubicTo(7.7f, 19.6f, 4.5f, 15.8f, 4.5f, 11f)
                path.lineTo(4.5f, 5.5f)
                path.close()
                c.drawPath(path, stroke)
                poly(c, 8.8f, 11.8f, 11f, 14f, 15.2f, 9.8f)
            }
            Ic.FINGERPRINT -> {
                arc(c, 3f, 3.5f, 21f, 21.5f, 200f, 140f)
                arc(c, 6f, 6.5f, 18f, 18.5f, 180f, 180f)
                line(c, 6f, 12.5f, 6f, 15.5f)
                line(c, 18f, 12.5f, 18f, 17f)
                arc(c, 9f, 9.5f, 15f, 15.5f, 180f, 180f)
                line(c, 9f, 12.5f, 9f, 18.5f)
                line(c, 15f, 12.5f, 15f, 20f)
                line(c, 12f, 12.5f, 12f, 21f)
            }
            Ic.VOLUME -> {
                path.reset()
                path.moveTo(3.5f, 9.5f)
                path.lineTo(7f, 9.5f)
                path.lineTo(11.5f, 5.5f)
                path.lineTo(11.5f, 18.5f)
                path.lineTo(7f, 14.5f)
                path.lineTo(3.5f, 14.5f)
                path.close()
                c.drawPath(path, stroke)
                arc(c, 11f, 8.5f, 18f, 15.5f, -50f, 100f)
                arc(c, 7.5f, 5f, 21.5f, 19f, -50f, 100f)
            }
            Ic.CLOCK -> {
                c.drawCircle(12f, 12f, 9f, stroke)
                poly(c, 12f, 7f, 12f, 12f, 15.5f, 14f)
            }
            Ic.DOCUMENT -> {
                rrect(c, 5.5f, 3f, 18.5f, 21f, 2.5f)
                line(c, 9f, 8f, 15f, 8f)
                line(c, 9f, 12f, 15f, 12f)
                line(c, 9f, 16f, 13f, 16f)
            }
            Ic.TUNE -> {
                line(c, 4f, 7f, 20f, 7f)
                line(c, 4f, 17f, 20f, 17f)
                c.drawCircle(9f, 7f, 2.6f, fill)
                c.drawCircle(15f, 17f, 2.6f, fill)
            }
            Ic.EYE -> {
                path.reset()
                path.moveTo(2f, 12f)
                path.cubicTo(5f, 6.5f, 19f, 6.5f, 22f, 12f)
                path.cubicTo(19f, 17.5f, 5f, 17.5f, 2f, 12f)
                path.close()
                c.drawPath(path, stroke)
                c.drawCircle(12f, 12f, 3f, stroke)
            }
            Ic.LOCK -> {
                rrect(c, 5f, 10.5f, 19f, 21f, 2.5f)
                arc(c, 8f, 3.5f, 16f, 11.5f, 180f, 180f)
                line(c, 8f, 7.5f, 8f, 10.5f)
                line(c, 16f, 7.5f, 16f, 10.5f)
                c.drawCircle(12f, 15.5f, 1.4f, fill)
            }
            Ic.PHONE -> {
                rrect(c, 6.5f, 2.5f, 17.5f, 21.5f, 2.5f)
                line(c, 10.5f, 18.5f, 13.5f, 18.5f)
            }
            Ic.BOLT -> {
                path.reset()
                path.moveTo(13f, 2.5f)
                path.lineTo(5.5f, 13.5f)
                path.lineTo(11.5f, 13.5f)
                path.lineTo(10.5f, 21.5f)
                path.lineTo(18.5f, 10.5f)
                path.lineTo(12.5f, 10.5f)
                path.close()
                c.drawPath(path, stroke)
            }
            Ic.REFRESH -> {
                arc(c, 5f, 5f, 19f, 19f, 35f, 290f)
                poly(c, 18.5f, 3.5f, 17.7f, 8f, 13.4f, 7.2f)
            }
            Ic.COPY -> {
                rrect(c, 9f, 9f, 20f, 20f, 2.5f)
                path.reset()
                path.moveTo(15f, 9f)
                path.lineTo(15f, 6.5f)
                path.quadTo(15f, 4f, 12.5f, 4f)
                path.lineTo(6.5f, 4f)
                path.quadTo(4f, 4f, 4f, 6.5f)
                path.lineTo(4f, 12.5f)
                path.quadTo(4f, 15f, 6.5f, 15f)
                path.lineTo(9f, 15f)
                c.drawPath(path, stroke)
            }
            Ic.SHARE -> {
                c.drawCircle(18f, 5.5f, 2.5f, stroke)
                c.drawCircle(6f, 12f, 2.5f, stroke)
                c.drawCircle(18f, 18.5f, 2.5f, stroke)
                line(c, 8.2f, 10.8f, 15.8f, 6.7f)
                line(c, 8.2f, 13.2f, 15.8f, 17.3f)
            }
        }
        c.restore()
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x1, y1, x2, y2, stroke)

    private fun poly(c: Canvas, vararg p: Float) {
        path.reset()
        path.moveTo(p[0], p[1])
        var i = 2
        while (i < p.size) {
            path.lineTo(p[i], p[i + 1])
            i += 2
        }
        c.drawPath(path, stroke)
    }

    private fun arc(c: Canvas, l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float) {
        rect.set(l, t, r, b)
        c.drawArc(rect, start, sweep, false, stroke)
    }

    private fun rrect(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float) {
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, rad, rad, stroke)
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
