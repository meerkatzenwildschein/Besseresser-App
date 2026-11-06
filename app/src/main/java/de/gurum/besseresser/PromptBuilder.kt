package de.gurum.besseresser

import android.content.Context

/**
 * Erstellung und Zusammenstellung lokalisierter Prompt-Templates für die KI-Kommunikation.
 */
object PromptBuilder {

    /**
     * Erstellt den Prompt für die Foto-Bewertung anhand der Nutzer-Ziele und des Prompt-Bundles.
     */
    fun createAnalyzePrompt(goals: List<String>, promptBundle: PromptBundle): String = buildString {
        appendLine(promptBundle.intro)
        goals.forEachIndexed { i, g -> appendLine("${i + 1}. $g") }
        if (promptBundle.noteHeader.isNotBlank()) {
            appendLine("")
            appendLine(promptBundle.noteHeader)
        }
        appendLine("")
        // JSON-Schlüssel (bewertung/ziel/ampel/begruendung/zusatz/erkannt) sind bewusst
        // fest verdrahtet – der Parser kennt diese Namen; nur die Anweisungssprache variiert.
        appendLine(promptBundle.formatIntro)
        appendLine("""{"bewertung":[{"ziel":"<exaktes Ziel>","ampel":"gruen|gelb|rot","begruendung":"<short reason>"}],"zusatz":"<one-sentence overall impression>","erkannt":"<one short sentence: food with estimated quantities, e.g. '100 g pasta with sauce, one apple'>"}""")
        appendLine(promptBundle.ampelMeaning)
        appendLine(promptBundle.exactlyOne)
        if (promptBundle.recognized.isNotBlank()) appendLine(promptBundle.recognized)
        if (promptBundle.honesty.isNotBlank()) appendLine(promptBundle.honesty)
        appendLine(promptBundle.responseLanguage)
    }

    /**
     * Erstellt den Prompt für das textbasierte Tages- oder Wochenfazit.
     */
    fun createDayFazitPrompt(
        goals: List<String>,
        promptContext: String?,
        mealLines: List<String>,
        prompts: FazitPromptBundle
    ): String = buildString {
        appendLine(prompts.promptIntro)
        goals.forEachIndexed { i, g -> appendLine("${i + 1}. $g") }
        promptContext?.takeIf { it.isNotBlank() }?.let {
            appendLine("")
            appendLine(it)
        }
        appendLine("")
        mealLines.forEach { appendLine(it) }
        appendLine("")
        appendLine(prompts.promptRules)
        if (prompts.promptHonesty.isNotBlank()) appendLine(prompts.promptHonesty)
        appendLine("")
        // JSON-Schlüssel (ampel/text) fest verdrahtet, Sprache via promptLanguage
        appendLine("""{"ampel":"gruen|gelb|rot","text":"<2-3 sentences: day summary in the app language, directly to the point>"}""")
        appendLine(prompts.promptLanguage)
    }

    /**
     * Erstellt den Prompt für die reine Textanalyse (Nacherfassung ohne Foto).
     */
    fun createAnalyzeTextPrompt(
        ctx: Context,
        goals: List<String>,
        userDescription: String
    ): String = buildString {
        appendLine(ctx.getString(R.string.prompt_eval_intro_textonly))
        goals.forEachIndexed { i, g -> appendLine("${i + 1}. $g") }
        appendLine("")
        appendLine(ctx.getString(R.string.prompt_textonly_context, userDescription.trim()))
        appendLine("")
        appendLine(ctx.getString(R.string.prompt_eval_format))
        appendLine("""{"bewertung":[{"ziel":"<exaktes Ziel>","ampel":"gruen|gelb|rot","begruendung":"<short reason>"}],"zusatz":"<one-sentence overall impression>","erkannt":"<one short sentence: what the description says the meal was, with quantities>"}""")
        appendLine(ctx.getString(R.string.prompt_eval_ampel_meaning))
        appendLine(ctx.getString(R.string.prompt_eval_exactly_one))
        appendLine(ctx.getString(R.string.prompt_honesty))
        appendLine(ctx.getString(R.string.prompt_eval_language))
    }

    /**
     * Erstellt den Prompt für die KI-gestützte Zerlegung von Zielen.
     */
    fun createSplitGoalsPrompt(request: SplitGoalsRequest): String = buildString {
        appendLine(request.splitIntro)
        appendLine(request.splitRules)
        appendLine("Reply ONLY with a JSON object (no text before or after):")
        appendLine("""{"ziele":["Goal 1","Goal 2"]}""")
        appendLine()
        appendLine(request.rawGoals)
    }
}
