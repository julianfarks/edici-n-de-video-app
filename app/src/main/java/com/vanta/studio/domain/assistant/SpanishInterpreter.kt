package com.vanta.studio.domain.assistant

import com.vanta.studio.domain.effects.Grade

/**
 * Offline interpreter for editing instructions in Spanish. It is deliberately a transparent set
 * of rules, not a language model: it understands the commands listed in the help text, shows
 * exactly what it understood, and reports every piece of the sentence it did not understand.
 */
object SpanishInterpreter {
    private val QUOTE = Regex("[\"“«']([^\"”»']{1,200})[\"”»']")
    private const val VERBS = "(?:pon|ponle|agrega|agr[eé]gale|a[ñn]ade|haz|hazlo|quita|elimina|corta|divide|acelera|baja|sube|aplica|cambia|genera|crea|silencia|mutea|edita|dale|activa)\\b"
    private val SEPARATORS = Regex("[.;\\n]| y luego | y despu[eé]s | luego | despu[eé]s | y tambi[eé]n | adem[aá]s |,?\\s+y (?=$VERBS)|,\\s*(?=$VERBS)")

    fun fold(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    fun parse(input: String): Interpretation {
        // Protect quoted text (it may contain separators) behind placeholders.
        val quotes = ArrayList<String>()
        val protectedText = QUOTE.replace(input) { m -> quotes += m.groupValues[1]; "‹${quotes.lastIndex}›" }
        val ops = ArrayList<AssistantOp>()
        val unclear = ArrayList<String>()
        for (raw in protectedText.split(SEPARATORS)) {
            val frag = raw.trim()
            if (frag.isEmpty()) continue
            val before = ops.size
            fragment(restore(frag, quotes), fold(frag), quotes, ops)
            if (ops.size == before) unclear += restore(frag, quotes)
        }
        return Interpretation(ops, unclear)
    }

    private fun restore(s: String, quotes: List<String>) = Regex("‹(\\d+)›").replace(s) { quotes.getOrElse(it.groupValues[1].toInt()) { "" } }

    // ------------------------------------------------------------------ numbers and times

    private val WORDS = mapOf(
        "un" to 1.0, "uno" to 1.0, "una" to 1.0, "dos" to 2.0, "tres" to 3.0, "cuatro" to 4.0, "cinco" to 5.0, "seis" to 6.0,
        "siete" to 7.0, "ocho" to 8.0, "nueve" to 9.0, "diez" to 10.0, "quince" to 15.0, "veinte" to 20.0, "treinta" to 30.0,
        "medio" to 0.5, "media" to 0.5,
    )

    private fun num(s: String): Double? = s.replace(',', '.').toDoubleOrNull() ?: WORDS[s]

    private val NUM = "(\\d+(?:[.,]\\d+)?|un|uno|una|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|quince|veinte|treinta)"

    /** All times mentioned in the fragment (seconds), in order. */
    private fun times(f: String): List<Double> {
        val out = ArrayList<Pair<Int, Double>>()
        Regex("(\\d+):(\\d{2})").findAll(f).forEach { out += it.range.first to it.groupValues[1].toDouble() * 60 + it.groupValues[2].toDouble() }
        Regex("\\b(?:segundo|seg|s)\\s*$NUM(?![\\d:])").findAll(f).forEach { m -> num(m.groupValues[1])?.let { out += m.range.first to it } }
        Regex("$NUM\\s*(?:segundos?|seg\\b|s\\b)").findAll(f).forEach { m -> num(m.groupValues[1])?.let { out += m.range.first to it } }
        Regex("$NUM\\s*minutos?").findAll(f).forEach { m -> num(m.groupValues[1])?.let { out += m.range.first to it * 60 } }
        return out.sortedBy { it.first }.map { it.second }.distinct()
    }

    private val RANGE = Regex("(?:del|desde(?: el)?|entre(?: el)?)\\s*(?:segundo\\s*)?(\\d+(?:[.,]\\d+)?)(?:\\s*(?:s|seg|segundos?))?\\s*(?:al|hasta(?: el)?|y(?: el)?|a)\\s*(?:segundo\\s*)?(\\d+(?:[.,]\\d+)?)")

    private fun clipRef(f: String): Int? {
        Regex("clip\\s*(?:numero\\s*|n\\.?\\s*|#)?(\\d+)").find(f)?.let { return it.groupValues[1].toInt() }
        return when {
            Regex("primer[oa]?\\s+(?:clip|video|plano)").containsMatchIn(f) -> 1
            Regex("segund[oa]\\s+(?:clip|video|plano)").containsMatchIn(f) -> 2
            Regex("tercer[oa]?\\s+(?:clip|video|plano)").containsMatchIn(f) -> 3
            else -> null
        }
    }

    // ------------------------------------------------------------------ one fragment

    private fun has(f: String, vararg words: String) = words.any { f.contains(it) }
    private fun hasRe(f: String, re: String) = Regex(re).containsMatchIn(f)

    private fun fragment(original: String, f: String, quotes: List<String>, ops: MutableList<AssistantOp>) {
        val removeVerb = hasRe(f, "\\b(quit|elimin|borr|cort|remuev|sac|recort)\\w*")

        // --- tools that need analysis
        if (has(f, "silenc", "pausa") && removeVerb && !has(f, "silencia ", "mutea")) {
            ops += AssistantOp(Ops.OPEN_TOOL, name = "silence")
        }
        if (has(f, "subtitul", "subtit", "caption", "transcrib")) {
            var added = false
            if (hasRe(f, "\\b(gener|anad|agreg|pon|cre|automatic|transcrib|quier|necesit|hac|activ)\\w*") && !has(f, "cambi")) {
                ops += AssistantOp(Ops.OPEN_TOOL, name = "subtitles")
                added = true
            }
            val style = when {
                has(f, "karaoke") -> "KARAOKE"
                has(f, "cinemat") -> "CINEMATIC"
                has(f, "minimal") -> "MINIMAL"
                has(f, "creador", "tiktok", "viral") -> "CREATOR"
                has(f, "rebote", "bounce") -> "BOUNCE"
                has(f, "progresiv") -> "PROGRESSIVE"
                has(f, "resalt", "destac") -> "HIGHLIGHT"
                has(f, "escala") -> "SCALE_SYNC"
                else -> null
            }
            if (style != null) { ops += AssistantOp(Ops.SUBTITLE_STYLE, name = style); added = true }
            if (!added) ops += AssistantOp(Ops.OPEN_TOOL, name = "subtitles")
            return
        }
        val rhythm = has(f, "ritmo", "beat", "golpe", "musica", "compas", "bombo", "cancion")
        val fxTypes = ArrayList<String>()
        if (has(f, "flash negro")) fxTypes += "FLASH_BLACK" else if (has(f, "flash", "destello", "fogonazo")) fxTypes += "FLASH_WHITE"
        if (has(f, "zoom")) fxTypes += "IMPACT_ZOOM"
        if (has(f, "glitch")) fxTypes += "GLITCH"
        if (has(f, "sacud", "shake", "vibra", "temblor")) fxTypes += "SHAKE"
        if (has(f, "resplandor", "glow", "brillo")) fxTypes += "GLOW"
        if (has(f, "bloom")) fxTypes += "BLOOM"
        if (has(f, "aberracion")) fxTypes += "CHROMATIC"
        if (has(f, "desenfoque de movimiento", "motion blur")) fxTypes += "MOTION_BLUR"
        if (fxTypes.isNotEmpty()) {
            if (rhythm) {
                ops += AssistantOp(Ops.FX_ON_BEATS, name = fxTypes.joinToString(","))
            } else {
                val t = times(f).firstOrNull()
                for (type in fxTypes) ops += AssistantOp(Ops.ADD_FX, timeSec = t, name = type)
            }
            return
        }
        if (rhythm && hasRe(f, "\\b(edit|mont|cort|sincroniz|ajust|adapt)\\w*")) {
            val mode = when {
                has(f, "muy rapido", "frenetic") -> "VERY_FAST"
                has(f, "intens") -> "INTENSE"
                has(f, "dinamic", "rapid") -> "DYNAMIC"
                has(f, "cinemat") -> "CINEMATIC"
                has(f, "suave", "lent", "tranquil") -> "SOFT"
                else -> null
            }
            ops += AssistantOp(Ops.OPEN_TOOL, name = "auto_edit", text = mode)
            return
        }

        // --- direct edits
        val range = RANGE.find(f)
        if (range != null && removeVerb) {
            val a = num(range.groupValues[1]); val b = num(range.groupValues[2])
            if (a != null && b != null) { ops += AssistantOp(Ops.DELETE_RANGE, timeSec = minOf(a, b), endSec = maxOf(a, b)); return }
        }
        if (hasRe(f, "\\b(acelera|velocidad|camara lenta|slow|ralentiz|mas rapido|mas lento|timelapse)\\w*") || hasRe(f, "\\bx\\s*\\d") || hasRe(f, "\\d\\s*x\\b")) {
            val v = when {
                Regex("x\\s*(\\d+(?:[.,]\\d+)?)").find(f) != null -> num(Regex("x\\s*(\\d+(?:[.,]\\d+)?)").find(f)!!.groupValues[1])
                Regex("(\\d+(?:[.,]\\d+)?)\\s*x\\b").find(f) != null -> num(Regex("(\\d+(?:[.,]\\d+)?)\\s*x\\b").find(f)!!.groupValues[1])
                Regex("$NUM\\s*veces").find(f) != null -> num(Regex("$NUM\\s*veces").find(f)!!.groupValues[1])
                Regex("velocidad\\s*(?:a|de)?\\s*(\\d+(?:[.,]\\d+)?)").find(f) != null -> num(Regex("velocidad\\s*(?:a|de)?\\s*(\\d+(?:[.,]\\d+)?)").find(f)!!.groupValues[1])
                has(f, "doble") -> 2.0
                has(f, "triple") -> 3.0
                has(f, "mitad") -> 0.5
                has(f, "camara lenta", "slow", "ralentiz", "mas lento") -> 0.5
                has(f, "acelera", "mas rapido", "timelapse") -> 2.0
                else -> null
            }
            if (v != null) { ops += AssistantOp(Ops.SPEED, value = v, clip = clipRef(f)); return }
        }
        if (has(f, "volumen")) {
            val pct = Regex("(\\d+)\\s*%").find(f)?.groupValues?.get(1)?.toDouble()?.div(100)
            val v = pct ?: when {
                hasRe(f, "\\b(sub|aument|mas)\\w*") -> 1.5
                hasRe(f, "\\b(baj|reduc|men)\\w*") -> 0.5
                else -> null
            }
            if (v != null) { ops += AssistantOp(Ops.VOLUME, value = v, clip = clipRef(f)); return }
        }
        if ((hasRe(f, "\\b(silencia|mutea|apaga)\\w*") && !has(f, "silencios")) || hasRe(f, "(quita|sin|elimina)\\s+(el\\s+)?(audio|sonido)")) {
            ops += AssistantOp(Ops.MUTE, flag = true, clip = clipRef(f)); return
        }
        if (has(f, "texto", "titulo", "letrero", "rotulo")) {
            val q = Regex("‹(\\d+)›").find(f)
            val text = q?.let { quotes.getOrNull(it.groupValues[1].toInt()) } ?: quotes.firstOrNull()
            if (text != null) {
                val ts = times(f.replace(Regex("durante\\s+$NUM\\s*(?:segundos?|s\\b)"), ""))
                val dur = Regex("durante\\s+$NUM\\s*(?:segundos?|s\\b)").find(f)?.let { num(it.groupValues[1]) }
                ops += AssistantOp(Ops.ADD_TEXT, text = text, timeSec = ts.firstOrNull(), value = dur)
                return
            }
        }
        val aspect = when {
            has(f, "9:16", "vertical", "reels", "tiktok", "shorts", "historia", "stories") -> "9:16"
            has(f, "1:1", "cuadrad") -> "1:1"
            has(f, "4:5") -> "4:5"
            has(f, "16:9", "horizontal", "youtube", "panoramic") -> "16:9"
            else -> null
        }
        if (aspect != null) { ops += AssistantOp(Ops.ASPECT, name = aspect); return }
        if (has(f, "filtro", "look", "estilo", "color", "tono")) {
            val id = Grade.filters.firstOrNull { f.contains(it.id.replace('_', ' ')) || f.contains(it.id) }?.id ?: when {
                has(f, "calid") -> "warm"
                has(f, "frio") -> "cool"
                has(f, "cine", "cinematograf") -> "teal_orange"
                has(f, "blanco y negro", "b/n", "monocrom") -> "mono"
                has(f, "vintage", "antigu") -> "film70"
                has(f, "noche", "nocturn") -> "night"
                has(f, "atardecer") -> "sunset"
                else -> null
            }
            if (id != null) { ops += AssistantOp(Ops.FILTER, name = id, clip = clipRef(f)); return }
        }
        if (hasRe(f, "\\b(corta|divide|parte|separa|trocea|dividir|cortar)\\b")) {
            val t = times(f).firstOrNull()
            if (t != null) { ops += AssistantOp(Ops.SPLIT, timeSec = t); return }
            if (has(f, "aqui", "cursor", "ahora", "posicion actual")) { ops += AssistantOp(Ops.SPLIT); return }
        }
    }

    /** Short help listing what the offline interpreter can do (shown in the panel). */
    val EXAMPLES = listOf(
        "Quita los silencios y agrega subtítulos estilo karaoke",
        "Pon un flash y un zoom en cada golpe del ritmo",
        "Corta en el segundo 5",
        "Elimina del segundo 10 al 15",
        "Acelera el primer clip x2",
        "Agrega el texto \"Hola mundo\" en el segundo 2 durante 3 segundos",
        "Hazlo vertical para TikTok y pon filtro cálido",
        "Edita al ritmo de la música, muy rápido",
    )
}
