package de.gurum.besseresser.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import de.gurum.besseresser.model.Gender
import de.gurum.besseresser.model.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// ----------------------------------------------------------------- Interface

interface ProfileRepository {
    val profile: Flow<Profile>
    suspend fun currentProfile(): Profile
    suspend fun save(profile: Profile)
}

// ----------------------------------------------------------------- Implementation

class DefaultProfileRepository(private val dataStore: DataStore<Preferences>) : ProfileRepository {

    private object Keys {
        val BIRTH_DATE = stringPreferencesKey("birthDate")
        val WEIGHT = stringPreferencesKey("profileWeight")
        val HEIGHT = intPreferencesKey("profileHeight")
        val NOTES = stringPreferencesKey("profileNotes")
        val GENDER = stringPreferencesKey("profileGender")
    }

    override val profile: Flow<Profile> = dataStore.data.map { p -> p.toProfile() }

    private fun Preferences.toProfile(): Profile = Profile(
        birthDate = this[Keys.BIRTH_DATE]?.takeIf { it.isNotBlank() },
        weightKg = this[Keys.WEIGHT]?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 },
        heightCm = this[Keys.HEIGHT]?.takeIf { it > 0 },
        notes = this[Keys.NOTES] ?: "",
        gender = Gender.fromString(this[Keys.GENDER])
    )

    override suspend fun currentProfile(): Profile = dataStore.data.first().toProfile()

    /** Leere Eingaben speichern als "nicht gesetzt" – das Profil darf komplett leer bleiben.
     *  (birthDate/weight bleiben bei "nicht gesetzt" als leere Strings erhalten, height als -1 –
     *  identisch zur früheren SharedPreferences-Schreibweise.) */
    override suspend fun save(profile: Profile) {
        dataStore.edit { p ->
            p[Keys.BIRTH_DATE] = profile.birthDate?.takeIf { it.isNotBlank() } ?: ""
            p[Keys.WEIGHT] = profile.weightKg?.toString() ?: ""
            p[Keys.HEIGHT] = profile.heightCm ?: -1
            p[Keys.NOTES] = profile.notes.trim()
            p[Keys.GENDER] = profile.gender.rawValue
        }
    }
}
