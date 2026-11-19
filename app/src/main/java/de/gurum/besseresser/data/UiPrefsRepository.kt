package de.gurum.besseresser.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.gurum.besseresser.model.AppLanguage
import de.gurum.besseresser.model.AppThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// ----------------------------------------------------------------- Interface

interface UiPrefsRepository {
    val themeMode: Flow<AppThemeMode>
    val language: Flow<AppLanguage>
    suspend fun currentThemeMode(): AppThemeMode
    suspend fun currentLanguage(): AppLanguage
    suspend fun saveThemeMode(mode: AppThemeMode)
    suspend fun saveLanguage(language: AppLanguage)
}

// ----------------------------------------------------------------- Implementation

class DefaultUiPrefsRepository(private val dataStore: DataStore<Preferences>) : UiPrefsRepository {

    private object Keys {
        val THEME = stringPreferencesKey("appThemeMode")
        val LANGUAGE = stringPreferencesKey("appLanguage")
    }

    override val themeMode: Flow<AppThemeMode> = dataStore.data.map { p ->
        AppThemeMode.fromString(p[Keys.THEME])
    }

    override val language: Flow<AppLanguage> = dataStore.data.map { p ->
        AppLanguage.resolve(p[Keys.LANGUAGE])
    }

    override suspend fun currentThemeMode(): AppThemeMode = themeMode.first()

    override suspend fun currentLanguage(): AppLanguage = language.first()

    override suspend fun saveThemeMode(mode: AppThemeMode) {
        dataStore.edit { p -> p[Keys.THEME] = mode.rawValue }
    }

    override suspend fun saveLanguage(language: AppLanguage) {
        dataStore.edit { p -> p[Keys.LANGUAGE] = language.rawValue }
    }
}
