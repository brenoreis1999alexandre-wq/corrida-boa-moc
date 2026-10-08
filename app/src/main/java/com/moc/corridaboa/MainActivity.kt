package com.moc.corridaboa

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var pageHost: FrameLayout
    private lateinit var gas: EditText
    private lateinit var consumo: EditText
    private lateinit var targetKm: EditText
    private lateinit var targetHour: EditText
    private lateinit var targetMinute: EditText
    private var accessStatusLabel: TextView? = null
    private var overlayStatusLabel: TextView? = null
    private var selectedTab = 0
    private var historyFilter = 1 // current month
    private val prefs by lazy { getSharedPreferences(Prefs.FILE, MODE_PRIVATE) }

    private val bg = Color.rgb(13, 19, 36)
    private val surface = Color.rgb(27, 37, 62)
    private val surface2 = Color.rgb(37, 49, 79)
    private val accent = Color.rgb(71, 226, 188)
    private val lime = Color.rgb(67, 222, 145)
    private val pale = Color.rgb(234, 241, 255)
    private val secondary = Color.rgb(158, 174, 209)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        pageHost = FrameLayout(this)
        root.addView(pageHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomNavigation(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)))
        setContentView(root)
        renderTab()
    }

    override fun onResume() {
        super.onResume()
        if (!::pageHost.isInitialized) return
        if (selectedTab == 0) renderTab()
        accessStatusLabel?.text = "Acessibilidade: ${if (isServiceEnabled()) "ativada" else "pendente"}"
        overlayStatusLabel?.text = "Janela flutuante: ${if (Settings.canDrawOverlays(this)) "permitida" else "pendente"}"
    }

    private fun renderTab() {
        if (!::pageHost.isInitialized) return
        pageHost.removeAllViews()
        accessStatusLabel = null
        overlayStatusLabel = null
        val page = when (selectedTab) {
            1 -> historyPage()
            2 -> calculatorPage()
            3 -> settingsPage()
            else -> dashboardPage()
        }
        pageHost.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun bottomNavigation(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(7), dp(5), dp(7), dp(6))
        setBackgroundColor(Color.rgb(20, 29, 49))
        val items = listOf("⌂" to "Painel", "▤" to "Histórico", "▣" to "Calculadora", "⚙" to "Ajustes")
        items.forEachIndexed { index, item ->
            val navItem = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                val active = selectedTab == index
                val tint = if (active) accent else secondary
                addView(TextView(this@MainActivity).apply {
                    text = item.first
                    textSize = 20f
                    setTextColor(tint)
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    text = item.second
                    textSize = 11f
                    setTextColor(tint)
                    gravity = Gravity.CENTER
                })
                setOnClickListener {
                    selectedTab = index
                    renderTab()
                }
            }
            addView(navItem, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun dashboardPage(): View {
        val content = vertical()
        val records = RideHistory(this).latest(1000)
        val todayStart = startOfToday()
        val todays = records.filter { it.createdAt >= todayStart }
        val gross = todays.sumOf { it.fare }
        val net = todays.sumOf { it.net }
        val km = todays.sumOf { it.pickupKm + it.tripKm }
        val minutes = todays.sumOf { it.minutes }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, 0, 0) }
        brandText.addView(label("Olá, motorista", 23, pale, true))
        brandText.addView(label("RotaLume • Montes Claros", 13, accent, true))
        header.addView(brandText)
        content.addView(header)
        content.addView(space(10))
        val monitorOn = prefs.getBoolean(Prefs.MONITORING_ENABLED, true)
        val toggleCard = card()
        toggleCard.addView(label(if (monitorOn) "MONITORAMENTO LIGADO" else "MONITORAMENTO DESLIGADO", 14, if (monitorOn) lime else secondary, true))
        toggleCard.addView(space(6))
        toggleCard.addView(actionButton(if (monitorOn) "Desligar monitoramento" else "Ligar monitoramento") {
            val newValue = !prefs.getBoolean(Prefs.MONITORING_ENABLED, true)
            CorridaBoaAccessibilityService.setMonitoringFromActivity(this, newValue)
            Toast.makeText(this, if (newValue) "Monitoramento ligado" else "Monitoramento desligado", Toast.LENGTH_SHORT).show()
            renderTab()
        })
        content.addView(toggleCard)
        content.addView(space(10))

        val dailyCard = card()
        dailyCard.addView(label("RESUMO DE HOJE", 13, accent, true))
        dailyCard.addView(label(net.money(), 34, pale, true))
        dailyCard.addView(label("Líquido estimado após combustível", 13, secondary))
        dailyCard.addView(space(12))
        dailyCard.addView(metricRow(listOf(
            "OFERTAS" to todays.size.toString(),
            "BRUTO" to gross.money(),
            "KM" to "${km.oneDecimal()}"
        )))
        dailyCard.addView(space(7))
        dailyCard.addView(label("Tempo das ofertas: ${minutes.oneDecimal()} min", 12, secondary))
        dailyCard.addView(label("Estimativa das ofertas analisadas; não confirma corridas realizadas.", 11, secondary))
        content.addView(dailyCard)
        content.addView(space(10))
        val calcCard = card()
        calcCard.addView(label("CALCULADORA DE GANHOS", 14, accent, true))
        calcCard.addView(label("Calcule um valor por km considerando combustível, custos mensais e sua meta.", 12, secondary))
        calcCard.addView(actionButton("Abrir calculadora de ganhos") { selectedTab = 2; renderTab() })
        content.addView(calcCard)
        content.addView(space(14))

        val serviceCard = card()
        serviceCard.addView(label("MONITORAMENTO EM TEMPO REAL", 15, pale, true))
        serviceCard.addView(space(5))
        val accessOn = isServiceEnabled()
        val overlayOn = Settings.canDrawOverlays(this)
        val statusText = when {
            !monitorOn -> "MONITORAMENTO DESLIGADO"
            accessOn && overlayOn -> "ATIVO — pronto para ler ofertas"
            else -> "PRECISA DE PERMISSÃO"
        }
        serviceCard.addView(label(statusText, 14, if (monitorOn && accessOn && overlayOn) lime else Color.rgb(255, 205, 92), true))
        serviceCard.addView(space(7))
        serviceCard.addView(label("A leitura automática só funciona dentro de uma oferta ativa do Uber, 99 ou inDrive.", 13, secondary))
        serviceCard.addView(space(8))
        serviceCard.addView(actionButton("Configurar permissões") { permissionDialog() })
        content.addView(serviceCard)
        content.addView(space(14))

        val appsCard = card()
        appsCard.addView(label("APPS MONITORADOS", 14, pale, true))
        appsCard.addView(label("Uber Driver  •  99 Motorista  •  inDrive", 13, accent))
        appsCard.addView(label("A leitura depende das informações que cada app disponibiliza à Acessibilidade.", 12, secondary))
        content.addView(appsCard)
        content.addView(space(15))
        content.addView(label("O RotaLume só recomenda. Não aceita nem recusa corridas.", 12, secondary))

        return scroll(content)
    }

    private fun historyPage(): View {
        val content = vertical()
        content.addView(label("Histórico de ofertas", 24, pale, true))
        content.addView(label("Leituras e estimativas salvas neste aparelho", 13, secondary))
        content.addView(space(10))
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf("Hoje", "Este mês", "Tudo").forEachIndexed { index, title ->
            val chip = actionButton(title) {
                historyFilter = index
                renderTab()
            }
            chip.textSize = 12f
            chip.backgroundTintList = android.content.res.ColorStateList.valueOf(if (historyFilter == index) accent else surface2)
            chip.setTextColor(if (historyFilter == index) bg else pale)
            filters.addView(chip, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
        }
        content.addView(filters)
        content.addView(space(10))

        val allRecords = RideHistory(this).latest(1000)
        val now = System.currentTimeMillis()
        val filtered = when (historyFilter) {
            0 -> allRecords.filter { it.createdAt >= startOfToday() }
            1 -> allRecords.filter { it.createdAt >= startOfMonth() }
            else -> allRecords
        }
        val stats = card()
        stats.addView(label("LÍQUIDO ESTIMADO DAS OFERTAS", 12, accent, true))
        stats.addView(label(filtered.sumOf { it.net }.money(), 29, pale, true))
        stats.addView(metricRow(listOf(
            "OFERTAS" to filtered.size.toString(),
            "KM" to filtered.sumOf { it.pickupKm + it.tripKm }.oneDecimal(),
            "TEMPO" to "${filtered.sumOf { it.minutes }.oneDecimal()} min"
        )))
        content.addView(stats)
        content.addView(space(8))
        content.addView(actionButton("Apagar histórico") {
            AlertDialog.Builder(this)
                .setTitle("Apagar histórico?")
                .setMessage("Isso remove todas as ofertas salvas neste aparelho.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Apagar") { _, _ ->
                    RideHistory(this).deleteAll()
                    Toast.makeText(this, "Histórico apagado", Toast.LENGTH_SHORT).show()
                    renderTab()
                }.show()
        }.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(surface2)
            setTextColor(pale)
        })
        content.addView(space(7))
        if (filtered.isEmpty()) {
            val empty = card()
            empty.addView(label("Ainda não há ofertas neste período.", 15, pale, true))
            empty.addView(label("Quando uma oferta compatível aparecer, ela será registrada aqui.", 13, secondary))
            content.addView(empty)
        } else {
            filtered.forEach { content.addView(recordCard(it)); content.addView(space(8)) }
        }
        return scroll(content)
    }

    private fun calculatorPage(): View {
        val content = vertical()
        content.addView(label("Calculadora de ganhos", 24, pale, true))
        content.addView(label("Descubra quanto a oferta precisa render por km para cobrir seus custos e sua meta.", 13, secondary))
        content.addView(space(10))

        val inputs = card()
        inputs.addView(label("DADOS DO SEU CARRO", 14, accent, true))
        val consumption = editField(inputs, "Seu carro faz quantos km/L?", Prefs.CONSUMO, 12.0)
        val fuel = editField(inputs, "Preço da gasolina na sua cidade (R$/L)", Prefs.GAS, 6.20)
        val fixedCost = editField(inputs, "Aluguel ou financiamento mensal (R$; opcional)", Prefs.MONTHLY_FIXED, 0.0)
        val monthlyDistance = editField(inputs, "Quantos km você roda no mês?", Prefs.MONTHLY_KM, 0.0)
        val desiredNet = editField(inputs, "Quanto quer que sobre para você por km?", Prefs.CALC_GOAL_PER_KM, 2.0)
        inputs.addView(label("Se informar aluguel/financiamento, preencha também os km rodados no mês para dividir esse custo.", 12, secondary))
        content.addView(inputs)
        content.addView(space(9))

        var targetAfterFuelPerKm = 0.0
        val resultCard = card().apply { visibility = View.GONE }
        val result = label("", 15, pale)
        resultCard.addView(label("SEU RESULTADO", 13, accent, true))
        resultCard.addView(result)
        content.addView(resultCard)
        val applyButton = actionButton("Usar resultado nas metas por km") {
            prefs.edit().putFloat(Prefs.MIN_KM, targetAfterFuelPerKm.toFloat()).apply()
            Toast.makeText(this, "Meta líquida por km atualizada", Toast.LENGTH_SHORT).show()
        }.apply { visibility = View.GONE }
        content.addView(applyButton)
        content.addView(space(8))
        content.addView(actionButton("Calcular meu km ideal") {
            val consumptionValue = parse(consumption.text.toString())
            val fuelValue = parse(fuel.text.toString())
            val fixedValue = parse(fixedCost.text.toString())
            val monthlyKmValue = parse(monthlyDistance.text.toString())
            val desiredValue = parse(desiredNet.text.toString())
            if (listOf(consumptionValue, fuelValue, fixedValue, monthlyKmValue, desiredValue).any { it == null } ||
                (consumptionValue ?: 0.0) <= 0.0 || (fuelValue ?: 0.0) <= 0.0 ||
                (fixedValue ?: -1.0) < 0.0 || (monthlyKmValue ?: -1.0) < 0.0 || (desiredValue ?: -1.0) < 0.0 ||
                ((fixedValue ?: 0.0) > 0.0 && (monthlyKmValue ?: 0.0) <= 0.0)) {
                Toast.makeText(this, "Confira os valores e informe km/mês se houver aluguel ou financiamento.", Toast.LENGTH_LONG).show()
                return@actionButton
            }
            val consumptionNumber = consumptionValue!!
            val fuelNumber = fuelValue!!
            val fixedNumber = fixedValue!!
            val monthlyKmNumber = monthlyKmValue!!
            val desiredNumber = desiredValue!!
            val fuelPerKm = fuelNumber / consumptionNumber
            val fixedPerKm = if (fixedNumber > 0.0) fixedNumber / monthlyKmNumber else 0.0
            val breakEvenPerKm = fuelPerKm + fixedPerKm
            targetAfterFuelPerKm = fixedPerKm + desiredNumber
            val idealGrossPerKm = breakEvenPerKm + desiredNumber
            prefs.edit()
                .putFloat(Prefs.GAS, fuelNumber.toFloat())
                .putFloat(Prefs.CONSUMO, consumptionNumber.toFloat())
                .putFloat(Prefs.MONTHLY_FIXED, fixedNumber.toFloat())
                .putFloat(Prefs.MONTHLY_KM, monthlyKmNumber.toFloat())
                .putFloat(Prefs.CALC_GOAL_PER_KM, desiredNumber.toFloat())
                .apply()
            result.text = "Combustível: ${fuelPerKm.money()}/km\nCusto mensal dividido por km: ${fixedPerKm.money()}/km\nPonto de equilíbrio: ${breakEvenPerKm.money()}/km\nIdeal da oferta: ${idealGrossPerKm.money()}/km (inclui sua meta de ${desiredNumber.money()}/km)"
            applyButton.text = "Usar ${targetAfterFuelPerKm.money()}/km como meta líquida nas recomendações"
            resultCard.visibility = View.VISIBLE
            applyButton.visibility = View.VISIBLE
        })
        content.addView(space(10))
        content.addView(label("Estimativa com combustível e custo mensal informado. Não inclui manutenção, pneus, impostos ou depreciação.", 12, secondary))
        return scroll(content)
    }

    private fun settingsPage(): View {
        val content = vertical()
        content.addView(label("Ajustes", 24, pale, true))
        content.addView(label("Custos e limites usados nas recomendações", 13, secondary))
        content.addView(space(10))

        val costCard = card()
        costCard.addView(label("CÁLCULO DE CUSTOS", 14, accent, true))
        gas = editField(costCard, "Preço da gasolina (R$/L)", Prefs.GAS, 6.20)
        consumo = editField(costCard, "Consumo médio (km/L)", Prefs.CONSUMO, 12.0)
        val costPerKm = label("Custo estimado de combustível: — / km", 13, pale, true)
        costCard.addView(space(5))
        costCard.addView(costPerKm)
        content.addView(costCard)
        content.addView(space(10))

        val thresholdCard = card()
        thresholdCard.addView(label("METAS DE RECOMENDAÇÃO", 14, accent, true))
        targetKm = editField(thresholdCard, "Ganhos líquidos mínimos por km (R$/km)", Prefs.MIN_KM, 2.0)
        targetHour = editField(thresholdCard, "Ganhos líquidos mínimos por hora (R$/h)", Prefs.BOA_HORA, 35.0)
        targetMinute = editField(thresholdCard, "Ganhos líquidos mínimos por minuto (R$/min)", Prefs.MIN_MINUTO, 0.58)
        thresholdCard.addView(label("A corrida boa precisa atingir as três metas. A média atinge parte delas; a ruim não atinge nenhuma ou deixa prejuízo.", 12, secondary))
        content.addView(thresholdCard)
        content.addView(space(9))
        content.addView(actionButton("Salvar configurações") {
            val pairs = listOf(gas to Prefs.GAS, consumo to Prefs.CONSUMO, targetKm to Prefs.MIN_KM,
                targetHour to Prefs.BOA_HORA, targetMinute to Prefs.MIN_MINUTO)
            val values = pairs.map { parse(it.first.text.toString()) }
            if (values.any { it == null } || (values[1] ?: 0.0) <= 0.0 || values.drop(2).any { (it ?: -1.0) < 0.0 }) {
                Toast.makeText(this, "Confira os números; o consumo deve ser maior que zero e as metas não podem ser negativas.", Toast.LENGTH_LONG).show()
                return@actionButton
            }
            prefs.edit().apply { pairs.forEachIndexed { index, pair -> putFloat(pair.second, values[index]!!.toFloat()) } }.apply()
            val cost = (values[0]!! / values[1]!!).money()
            costPerKm.text = "Custo estimado de combustível: $cost / km"
            Toast.makeText(this, "Configurações salvas", Toast.LENGTH_SHORT).show()
        })
        costPerKm.text = "Custo estimado de combustível: ${(prefs.getFloat(Prefs.GAS, 6.20f) / prefs.getFloat(Prefs.CONSUMO, 12f)).toDouble().money()} / km"
        content.addView(space(10))

        val accessCard = card()
        accessCard.addView(label("PERMISSÕES", 14, accent, true))
        val accessLabel = label("Acessibilidade: ${if (isServiceEnabled()) "ativada" else "pendente"}", 13, pale)
        val overlayLabel = label("Janela flutuante: ${if (Settings.canDrawOverlays(this)) "permitida" else "pendente"}", 13, pale)
        accessStatusLabel = accessLabel
        overlayStatusLabel = overlayLabel
        accessCard.addView(accessLabel)
        accessCard.addView(overlayLabel)
        accessCard.addView(space(5))
        accessCard.addView(actionButton("Configurar permissões") { permissionDialog() })
        content.addView(accessCard)
        content.addView(space(10))

        val apps = card()
        apps.addView(label("APPS MONITORADOS", 14, accent, true))
        listOf("Uber Driver", "99 Motorista", "inDrive").forEach { app ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(label("•  $app", 14, pale), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(label("ATIVO", 11, lime, true))
            apps.addView(row)
        }
        content.addView(apps)
        content.addView(space(10))
        val bubble = card()
        bubble.addView(label("BOLHA FLUTUANTE R$", 14, accent, true))
        bubble.addView(label("Arraste a bolinha para onde quiser. Toque para ler a tela atual por OCR; segure para ligar ou desligar o OCR contínuo (Android 11+). As imagens são processadas localmente; o modo contínuo pode gastar mais bateria.", 13, secondary))
        content.addView(bubble)
        content.addView(space(12))
        content.addView(label("O cálculo desconta combustível estimado. Não inclui manutenção, pneus, depreciação, impostos ou outros custos.", 12, secondary))
        return scroll(content)
    }

    private fun permissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Ativar monitoramento")
            .setMessage("1. Permita a janela flutuante.\n2. Ative RotaLume em Acessibilidade. Se estiver bloqueado, abra Informações do app > ⋮ > Permitir configurações restritas e tente novamente.\n3. Abra o app de motorista. A bolinha R$ aparece e pode ser arrastada.\n\nO app apenas lê a oferta e recomenda; não toca em aceitar ou recusar.")
            .setNegativeButton("Agora não", null)
            .setPositiveButton("Abrir permissões") { _, _ ->
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }.show()
    }

    private fun recordCard(r: RideRecord): LinearLayout {
        val color = when (r.status) {
            OverlayManager.GOOD -> lime
            OverlayManager.MAYBE -> Color.rgb(255, 206, 92)
            OverlayManager.BAD -> Color.rgb(255, 111, 121)
            else -> secondary
        }
        val title = when (r.status) {
            OverlayManager.GOOD -> "CORRIDA BOA — ACEITAR"
            OverlayManager.MAYBE -> "MÉDIA — AVALIAR"
            OverlayManager.BAD -> "CORRIDA RUIM — NÃO ACEITAR"
            else -> "LEITURA INCOMPLETA"
        }
        val card = card()
        card.addView(label(SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale("pt", "BR")).format(Date(r.createdAt)), 12, secondary))
        card.addView(label(title, 14, color, true))
        card.addView(label("${r.fare.money()}  •  ${ (r.pickupKm + r.tripKm).oneDecimal()} km  •  ${r.minutes.oneDecimal()} min", 14, pale, true))
        card.addView(label("Origem: ${r.pickup.ifBlank { "não identificada" }}", 12, secondary))
        card.addView(label("Destino: ${r.dropoff.ifBlank { "não identificado" }}", 12, secondary))
        card.addView(label("Combustível: ${r.fuelCost.money()}  •  Líquido estimado: ${r.net.money()}", 12, pale))
        card.addView(label("Líquido: ${r.netHour.money()}/h  •  ${r.netKm.money()}/km  •  ${r.netMinute.money()}/min", 12, secondary))
        return card
    }

    private fun metricRow(items: List<Pair<String, String>>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        items.forEachIndexed { index, pair ->
            val cell = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(7), dp(5), dp(4), dp(5))
                addView(label(pair.first, 10, secondary, true))
                addView(label(pair.second, 14, pale, true))
            }
            addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun editField(parent: LinearLayout, title: String, key: String, default: Double): EditText {
        parent.addView(label(title, 12, secondary))
        val current = prefs.getFloat(key, default.toFloat()).toString()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(true)
            setText(current)
            textSize = 15f
            setTextColor(pale)
            setHintTextColor(secondary)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(surface2, dp(10), Color.rgb(72, 89, 126))
        }
        parent.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(7) })
        return input
    }

    private fun actionButton(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 14f
        setTextColor(bg)
        backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(3) }
    }

    private fun vertical(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(17), dp(18), dp(18))
        setBackgroundColor(bg)
    }

    private fun scroll(content: LinearLayout): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setBackgroundColor(bg)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(13), dp(15), dp(13))
        background = rounded(surface, dp(21), Color.rgb(53, 70, 110))
        elevation = dp(2).toFloat()
    }

    private fun rounded(color: Int, radius: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), stroke)
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun space(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, CorridaBoaAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any {
            it.packageName == expected.packageName && it.className == expected.className
        }
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfMonth(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun parse(value: String): Double? = value.trim().replace(" ", "").replace(",", ".").toDoubleOrNull()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

internal object Prefs {
    const val FILE = "corrida_boa_settings"
    const val GAS = "gas_price"
    const val CONSUMO = "consumo"
    const val MIN_HORA = "min_hour"
    const val BOA_HORA = "good_hour"
    const val MIN_KM = "good_km"
    const val MIN_MINUTO = "good_minute"
    const val MONITORING_ENABLED = "monitoring_enabled"
    const val MONTHLY_FIXED = "monthly_fixed_cost"
    const val MONTHLY_KM = "monthly_distance_km"
    const val CALC_GOAL_PER_KM = "calculator_goal_per_km"
    const val OCR_CONTINUOUS = "ocr_continuous"
}
