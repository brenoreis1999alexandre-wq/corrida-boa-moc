package com.moc.corridaboa

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var gas: EditText
    private lateinit var consumo: EditText
    private lateinit var minHora: EditText
    private lateinit var boaHora: EditText
    private lateinit var minKm: EditText
    private lateinit var permissions: TextView
    private val prefs by lazy { getSharedPreferences(Prefs.FILE, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(15, 17, 21)
        window.navigationBarColor = Color.rgb(15, 17, 21)
        window.decorView.systemUiVisibility = 0

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            setBackgroundColor(Color.rgb(15, 17, 21))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)

        val brandHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandHeader.addView(ImageView(this).apply { setImageResource(R.drawable.ic_launcher) }, LinearLayout.LayoutParams(dp(68), dp(68)))
        val brandCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandCopy.addView(text("RotaLume", 25, Color.WHITE, true))
        brandCopy.addView(text("Seu copiloto de corridas • MOC", 13, Color.rgb(0, 214, 121), true))
        brandHeader.addView(brandCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(brandHeader)
        content.addView(text("Configure seus custos e os critérios das recomendações.", 15, Color.LTGRAY))
        content.addView(text("Os valores ficam salvos somente neste aparelho.", 13, Color.LTGRAY))

        gas = field(content, "Preço da gasolina (R$/L)", Prefs.GAS, "6,20")
        consumo = field(content, "Consumo do veículo (km/L)", Prefs.CONSUMO, "12")
        minHora = field(content, "Mínimo para considerar (R$/hora)", Prefs.MIN_HORA, "25")
        boaHora = field(content, "Meta para ‘PEGAR’ (R$/hora)", Prefs.BOA_HORA, "35")
        minKm = field(content, "Meta para ‘PEGAR’ (R$/km líquido)", Prefs.MIN_KM, "2,00")

        content.addView(button("Salvar configurações") {
            val values = listOf(gas to Prefs.GAS, consumo to Prefs.CONSUMO, minHora to Prefs.MIN_HORA,
                boaHora to Prefs.BOA_HORA, minKm to Prefs.MIN_KM)
            val parsed = values.map { (edit, _) -> parse(edit.text.toString()) }
            if (parsed.any { it == null } || (parsed[1] ?: 0.0) <= 0.0) {
                Toast.makeText(this, "Confira os números. O consumo precisa ser maior que zero.", Toast.LENGTH_LONG).show()
                return@button
            }
            prefs.edit().apply {
                values.forEachIndexed { index, pair -> putFloat(pair.second, parsed[index]!!.toFloat()) }
            }.apply()
            Toast.makeText(this, "Configurações salvas", Toast.LENGTH_SHORT).show()
        })

        content.addView(button("Ver histórico das ofertas") {
            startActivity(Intent(this, HistoryActivity::class.java))
        })

        content.addView(button("Permitir janela flutuante") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        content.addView(button("Ativar leitura de acessibilidade") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        permissions = text("", 14, Color.YELLOW)
        content.addView(permissions)
        content.addView(text("Como funciona", 18, Color.WHITE, true))
        content.addView(text("Quando uma oferta aparece no Uber Driver ou 99 Driver, o app tenta ler valor, quilômetros e minutos visíveis. Calcula o custo estimado de combustível e mostra uma aba com lucro, ganho por hora, minuto e km. A recomendação é apenas informativa: o app não toca nos botões e não aceita nem recusa corridas.", 14, Color.LTGRAY))
        content.addView(text("A leitura depende do texto que cada versão do app de motorista disponibiliza à Acessibilidade. Confira os números na tela antes de decidir; se algum dado não for reconhecido, o app avisa que a leitura ficou incompleta.", 14, Color.LTGRAY))
    }

    override fun onResume() { super.onResume(); if (::permissions.isInitialized) refreshPermissions() }

    private fun refreshPermissions() {
        val overlay = if (Settings.canDrawOverlays(this)) "permitida" else "pendente"
        val a11y = if (isServiceEnabled()) "ativada" else "pendente"
        permissions.text = "Sobreposição: $overlay   •   Acessibilidade: $a11y"
        permissions.setTextColor(if (overlay == "permitida" && a11y == "ativada") Color.GREEN else Color.YELLOW)
    }

    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/${CorridaBoaAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun field(parent: LinearLayout, label: String, key: String, fallback: String): EditText {
        parent.addView(text(label, 14, Color.LTGRAY))
        return EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setText(prefs.getFloat(key, parse(fallback)!!.toFloat()).toString().trimEnd('0').trimEnd('.'))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setHint(fallback)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 255, 136))
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { action() }
        isAllCaps = false
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 214, 121))
        setTextColor(Color.rgb(8, 20, 16))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(8) }
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(7), 0, dp(7))
    }

    private fun parse(value: String): Double? = value.trim().replace(" ", "").replace(",", ".").toDoubleOrNull()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

internal object Prefs {
    const val FILE = "corrida_boa_settings"
    const val GAS = "gas_price"
    const val CONSUMO = "consumo"
    const val MIN_HORA = "min_hour"
    const val BOA_HORA = "good_hour"
    const val MIN_KM = "good_km"
}
