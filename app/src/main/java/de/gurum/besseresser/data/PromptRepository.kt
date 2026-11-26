package de.gurum.besseresser.data

import android.content.Context
import de.gurum.besseresser.R
import de.gurum.besseresser.model.Gender
import de.gurum.besseresser.model.HistoryEntry
import de.gurum.besseresser.model.Profile
import de.gurum.besseresser.model.WeightedGoal
import de.gurum.besseresser.model.calendarWeekOf
import de.gurum.besseresser.model.toWeightedGoals
import de.gurum.besseresser.ui.DATE_FORMATTER
import de.gurum.besseresser.ui.formatTime
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

interface PromptRepository {
    fun createAnalyzePromptBundle(trimmedNote: String = ""): PromptBundle
    fun createAnalyzeTextPrompt(goals: List<WeightedGoal>, description: String): String
    fun createDayFazitPrompts(): FazitPromptBundle
    fun createWeekFazitPrompts(calendarWeek: Int = calendarWeekOf()): FazitPromptBundle
    fun dayFazitMealDescriptions(entriesOfDay: List<HistoryEntry>): List<String>
    fun weekFazitMealDescriptions(entriesOfWeek: List<HistoryEntry>): List<String>
    fun profileContextLine(profile: Profile): String?
    fun noInternetSavedUnratedMessage(): String = "Keine Internetverbindung – ohne KI-Bewertung fortgefahren."
    fun noInternetCannotRateMessage(): String = "Keine Internetverbindung – KI-Bewertung ist aktuell nicht möglich."
}

fun PromptRepository.createAnalyzeTextPrompt(goals: List<String>, description: String): String =
    createAnalyzeTextPrompt(goals.toWeightedGoals(), description)

class DefaultPromptRepository(
    private val context: Context
) : PromptRepository {

    override fun createAnalyzePromptBundle(trimmedNote: String): PromptBundle = PromptBundle(
        intro = context.getString(R.string.prompt_eval_intro),
        formatIntro = context.getString(R.string.prompt_eval_format),
        ampelMeaning = context.getString(R.string.prompt_eval_ampel_meaning),
        exactlyOne = context.getString(R.string.prompt_eval_exactly_one),
        responseLanguage = context.getString(R.string.prompt_eval_language),
        recognized = context.getString(R.string.prompt_eval_recognized),
        honesty = context.getString(R.string.prompt_honesty),
        noteHeader = if (trimmedNote.isNotBlank())
            context.getString(R.string.prompt_note_header, trimmedNote)
        else ""
    )

    override fun createAnalyzeTextPrompt(goals: List<WeightedGoal>, description: String): String {
        val textBundle = PromptBundle(
            intro = context.getString(R.string.prompt_eval_intro_textonly),
            formatIntro = context.getString(R.string.prompt_eval_format),
            ampelMeaning = context.getString(R.string.prompt_eval_ampel_meaning),
            exactlyOne = context.getString(R.string.prompt_eval_exactly_one),
            responseLanguage = context.getString(R.string.prompt_eval_language),
            honesty = context.getString(R.string.prompt_honesty)
        )
        return PromptBuilder.createAnalyzeTextPrompt(
            goals = goals,
            promptBundle = textBundle,
            textOnlyContext = context.getString(R.string.prompt_textonly_context, description.trim())
        )
    }

    override fun createDayFazitPrompts(): FazitPromptBundle = FazitPromptBundle(
        promptIntro = context.getString(R.string.prompt_dayfazit_intro),
        promptRules = context.getString(R.string.prompt_dayfazit_rules),
        promptLanguage = context.getString(R.string.prompt_dayfazit_language),
        promptHonesty = context.getString(R.string.prompt_honesty)
    )

    override fun createWeekFazitPrompts(calendarWeek: Int): FazitPromptBundle = FazitPromptBundle(
        promptIntro = context.getString(R.string.prompt_weekfazit_intro, calendarWeek),
        promptRules = context.getString(R.string.prompt_dayfazit_rules),
        promptLanguage = context.getString(R.string.prompt_dayfazit_language),
        promptHonesty = context.getString(R.string.prompt_honesty)
    )

    override fun dayFazitMealDescriptions(entriesOfDay: List<HistoryEntry>): List<String> =
        entriesOfDay.filter { it.isRated }.sortedBy { it.effectiveSortOrder }.map { e ->
            val time = if (e.backdated)
                context.getString(R.string.nacherfassen_label)
            else
                formatTime(e.dateMillis)
            val recog = e.recognizedMeal.ifBlank { context.getString(R.string.dayfazit_line_none) }
            var line = "$time · $recog"
            e.note.takeIf { it.isNotBlank() }
                ?.let { line += " · " + context.getString(R.string.dayfazit_line_note, it.trim()) }
            line
        }

    override fun weekFazitMealDescriptions(entriesOfWeek: List<HistoryEntry>): List<String> =
        entriesOfWeek.filter { it.isRated }.sortedWith(
            compareBy<HistoryEntry> {
                Instant.ofEpochMilli(it.dateMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            }.thenBy { it.effectiveSortOrder }
        ).map { e ->
            val dateStr = Instant.ofEpochMilli(e.dateMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(DATE_FORMATTER)
            val recog = e.recognizedMeal.ifBlank { context.getString(R.string.dayfazit_line_none) }
            var line = "$dateStr · $recog"
            e.note.takeIf { it.isNotBlank() }
                ?.let { line += " · " + context.getString(R.string.dayfazit_line_note, it.trim()) }
            line
        }

    override fun profileContextLine(profile: Profile): String? {
        val parts = mutableListOf<String>()
        profile.ageYears?.let { parts.add(context.resources.getQuantityString(R.plurals.profile_ctx_age, it, it)) }
        profile.weightKg?.let {
            val shown = if (abs(it % 1.0) < 0.001) "${it.toInt()}"
            else String.format(Locale.US, "%.1f", it)
            parts.add(context.getString(R.string.profile_ctx_weight, shown))
        }
        profile.heightCm?.let { parts.add(context.getString(R.string.profile_ctx_height, it)) }
        if (profile.gender != Gender.NONE) {
            val word = context.getString(
                when (profile.gender) {
                    Gender.MALE -> R.string.profile_gender_male
                    Gender.FEMALE -> R.string.profile_gender_female
                    else -> R.string.profile_gender_diverse
                }
            )
            parts.add(context.getString(R.string.profile_ctx_gender, word))
        }
        if (profile.notes.isNotBlank()) parts.add(context.getString(R.string.profile_ctx_notes, profile.notes.trim()))
        if (parts.isEmpty()) return null
        return context.getString(R.string.profile_ctx_header, parts.joinToString(" · "))
    }

    override fun noInternetSavedUnratedMessage(): String =
        context.getString(R.string.no_internet_saved_unrated)

    override fun noInternetCannotRateMessage(): String =
        context.getString(R.string.no_internet_cannot_rate)
}
