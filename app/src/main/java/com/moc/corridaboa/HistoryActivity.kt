package com.moc.corridaboa

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(15, 17, 21)
        window.navigationBarColor = Color.rgb(15, 17, 21)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(Color.rgb(15, 17, 21))
        }
        setContentView(ScrollView(this).apply { addView(list) })
        list.addView(label("Histórico de ofertas", 24, Color.WHITE, true))
        list.addView(label("Salvo somente neste aparelho", 14, Color.LTGRAY))
        list.addView(Button(this).apply {
            text = "Voltar"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        val records = RideHistory(this).latest(300)
        if (records.isEmpty()) {
            list.addView(label("Nenhuma oferta analisada ainda. Quando uma oferta compatível aparecer, ela será registrada aqui.", 16, Color.LTGRAY))
        } else {
            list.addView(label("${records.size} ofertas mais recentes", 16, Color.rgb(0, 255, 136), true))
            records.forEach { record -> list.addView(recordCard(record)) }
        }
    }

    private fun recordCard(r: RideRecord): TextView {
        val status = when (r.status) {
            OverlayManager.GOOD -> "PEGAR CORRIDA"
            OverlayManager.MAYBE -> "AVALIAR CORRIDA"
            OverlayManager.BAD -> "NÃO PEGAR CORRIDA"
            else -> "LEITURA INCOMPLETA"
        }
        val color = when (r.status) {
            OverlayManager.GOOD -> Color.rgb(0, 210, 110)
            OverlayManager.MAYBE -> Color.rgb(255, 205, 45)
            else -> Color.rgb(255, 95, 85)
        }
        val time = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(r.createdAt))
        val pickup = r.pickup.ifBlank { "Local de partida não identificado" }
        val dropoff = r.dropoff.ifBlank { "Destino não identificado" }
        val summary = """$time
$status
Origem: $pickup
Destino: $dropoff
Oferta: ${r.fare.money()}  •  Buscar: ${r.pickupKm.oneDecimal()} km  •  Viagem: ${r.tripKm.oneDecimal()} km
Total: ${(r.pickupKm + r.tripKm).oneDecimal()} km  •  ${r.minutes.oneDecimal()} min
Gasolina: ${r.fuelCost.money()}  •  Custos mensais rateados: ${r.monthlyCost.money()}
Lucro estimado: ${r.net.money()}
Valores brutos da oferta: ${r.grossKm.money()}/km  •  ${r.grossHour.money()}/h  •  ${r.grossMinute.money()}/min"""
        return TextView(this).apply {
            text = summary
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(32, 36, 45))
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
            contentDescription = "$status. $summary"
        }
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
