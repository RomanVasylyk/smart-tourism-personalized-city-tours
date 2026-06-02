package com.example.smarttourism.core.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

enum class AppLanguage(val tag: String) {
    ENGLISH("en"),
    SLOVAK("sk"),
    UKRAINIAN("uk"),
    CZECH("cs"),
    GERMAN("de"),
    POLISH("pl"),
    HUNGARIAN("hu");

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { language -> language.tag == tag } ?: ENGLISH
    }
}

object AppLanguageStore {
    private const val PreferencesName = "app_language_preferences"
    private const val LanguageTagKey = "language_tag"

    fun load(context: Context): AppLanguage {
        val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        return AppLanguage.fromTag(preferences.getString(LanguageTagKey, null))
    }

    fun save(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(LanguageTagKey, language.tag)
            .apply()
    }

    fun wrapContext(context: Context, language: AppLanguage): Context {
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        return context.createConfigurationContext(configuration)
    }

    @Suppress("DEPRECATION")
    fun applyToResources(context: Context, language: AppLanguage) {
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
    }
}
