package com.cursorforandroid.ui.voice

import android.content.Context
import androidx.core.content.edit

/** One of the voices the account offers a call (`RealtimeVoiceSessionPlan.voice_options`), by its id. */
data class VoiceOption(val id: String, val label: String)

/**
 * A call's voice, speed and language: the three rows of the settings menu on Cursor's call tray. [voice] is one of
 * the account's call voices by its id (null: the one the account minted the call with), [speed] one of [Speeds], and
 * [language] one of [Languages] by its tag ([AutoLanguage]: the call's transcription tells).
 */
data class VoiceCallSettings(
    val voice: String? = null,
    val speed: Float = 1f,
    val language: String = AutoLanguage,
) {
    /** The language as a BCP-47 tag, or null for Auto-detect. */
    val languageTag: String? get() = language.takeUnless { it == AutoLanguage }

    /** One of Cursor's speed choices. */
    data class Speed(val value: Float, val label: String)

    /** One of Cursor's language choices. */
    data class Language(val tag: String, val label: String)

    companion object {
        const val AutoLanguage = "auto"

        /** Cursor's own speed choices, as the tray's menu lists them. */
        val Speeds = listOf(Speed(0.75f, "0.75x"), Speed(1f, "1x"), Speed(1.25f, "1.25x"), Speed(1.5f, "1.5x"), Speed(2f, "2x"))

        /** Cursor's own language choices, Auto-detect first, as the tray's menu lists them. */
        val Languages = listOf(
            Language(AutoLanguage, "Auto-detect"),
            Language("en", "English"),
            Language("ar-EG", "Arabic (Egypt)"),
            Language("ar-SA", "Arabic (Saudi Arabia)"),
            Language("ar-AE", "Arabic (United Arab Emirates)"),
            Language("bn", "Bengali"),
            Language("ca", "Catalan"),
            Language("zh", "Chinese (Simplified)"),
            Language("da", "Danish"),
            Language("nl", "Dutch"),
            Language("fi", "Finnish"),
            Language("fr", "French"),
            Language("de", "German"),
            Language("hi", "Hindi"),
            Language("hu", "Hungarian"),
            Language("id", "Indonesian"),
            Language("it", "Italian"),
            Language("ja", "Japanese"),
            Language("ko", "Korean"),
            Language("pl", "Polish"),
            Language("pt-BR", "Portuguese (Brazil)"),
            Language("pt-PT", "Portuguese (Portugal)"),
            Language("ru", "Russian"),
            Language("es-MX", "Spanish (Mexico)"),
            Language("es-ES", "Spanish (Spain)"),
            Language("sv", "Swedish"),
            Language("th", "Thai"),
            Language("tr", "Turkish"),
            Language("vi", "Vietnamese"),
        )

        fun speedLabel(speed: Float): String = Speeds.firstOrNull { it.value == speed }?.label ?: "${speed}x"
        fun languageLabel(tag: String): String = Languages.firstOrNull { it.tag == tag }?.label ?: tag
    }
}

/** Where [VoiceCallSettings] are kept between calls. */
interface VoiceSettingsStore {
    fun load(): VoiceCallSettings
    fun save(settings: VoiceCallSettings)

    class InMemory(private var settings: VoiceCallSettings = VoiceCallSettings()) : VoiceSettingsStore {
        override fun load() = settings
        override fun save(settings: VoiceCallSettings) {
            this.settings = settings
        }
    }

    /** On the device, in the app's private preferences. */
    class Prefs(context: Context) : VoiceSettingsStore {
        private val prefs = context.applicationContext.getSharedPreferences("voice_call", Context.MODE_PRIVATE)

        override fun load() = VoiceCallSettings(
            voice = prefs.getString("voice", null),
            speed = prefs.getFloat("speed", 1f).takeIf { s -> VoiceCallSettings.Speeds.any { it.value == s } } ?: 1f,
            language = prefs.getString("language", null)?.takeIf { t -> VoiceCallSettings.Languages.any { it.tag == t } } ?: VoiceCallSettings.AutoLanguage,
        )

        override fun save(settings: VoiceCallSettings) {
            prefs.edit {
                putString("voice", settings.voice)
                putFloat("speed", settings.speed)
                putString("language", settings.language)
            }
        }
    }
}
