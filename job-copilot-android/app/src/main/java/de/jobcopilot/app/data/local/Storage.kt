package de.jobcopilot.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import de.jobcopilot.app.domain.CoverLetter
import de.jobcopilot.app.domain.Job
import de.jobcopilot.app.domain.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ApplicationStatus(val label: String) {
    SAVED("Saved"), APPLIED("Applied"), INTERVIEW("Interview"), OFFER("Offer"), REJECTED("Rejected")
}

@Entity(tableName = "applications")
data class ApplicationEntity(
    @PrimaryKey val id: String,
    val jobJson: String,
    val status: ApplicationStatus,
    val fitScore: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val followUpAt: Long? = null,
    val notes: String = "",
    val interviewNotes: String = "",
    val letterJson: String? = null,
)

// Extensions rather than members so Room doesn't try to map them to columns.
val ApplicationEntity.job: Job get() = AppJson.decodeFromString(jobJson)
val ApplicationEntity.letter: CoverLetter? get() = letterJson?.let { AppJson.decodeFromString(it) }

@Dao
interface ApplicationDao {
    @Query("SELECT * FROM applications ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ApplicationEntity>>

    @Query("SELECT * FROM applications WHERE id = :id")
    suspend fun get(id: String): ApplicationEntity?

    @Query("SELECT * FROM applications WHERE followUpAt IS NOT NULL AND followUpAt <= :now AND status IN ('APPLIED', 'INTERVIEW')")
    suspend fun dueFollowUps(now: Long): List<ApplicationEntity>

    @Upsert
    suspend fun upsert(entity: ApplicationEntity)

    @Query("DELETE FROM applications WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM applications")
    suspend fun deleteAll()
}

@Database(entities = [ApplicationEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun applications(): ApplicationDao

    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "jobcopilot.db").build()
    }
}

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Profile and settings live in encrypted preferences: a CV is personal data, and the API key is a secret.
 * Nothing leaves the device except the job-board queries and, when the user generates a letter,
 * the posting + profile sent to the Claude API.
 */
class SecureStore(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "jobcopilot_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _profile = MutableStateFlow(loadProfile())
    val profile: StateFlow<Profile> = _profile

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings

    private fun loadProfile(): Profile =
        prefs.getString(KEY_PROFILE, null)?.let { runCatching { AppJson.decodeFromString<Profile>(it) }.getOrNull() } ?: Profile()

    private fun loadSettings() = Settings(
        claudeApiKey = prefs.getString(KEY_API, "").orEmpty(),
        greenhouseBoards = prefs.getString(KEY_GH, null)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
            ?: Settings.DEFAULT_GREENHOUSE,
        leverCompanies = prefs.getString(KEY_LEVER, null)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
            ?: Settings.DEFAULT_LEVER,
        useArbeitnow = prefs.getBoolean(KEY_ARBEITNOW, true),
    )

    fun saveProfile(profile: Profile) {
        prefs.edit().putString(KEY_PROFILE, AppJson.encodeToString(profile)).apply()
        _profile.value = profile
    }

    fun saveSettings(settings: Settings) {
        prefs.edit()
            .putString(KEY_API, settings.claudeApiKey.trim())
            .putString(KEY_GH, settings.greenhouseBoards.joinToString(","))
            .putString(KEY_LEVER, settings.leverCompanies.joinToString(","))
            .putBoolean(KEY_ARBEITNOW, settings.useArbeitnow)
            .apply()
        _settings.value = settings
    }

    fun wipe() {
        prefs.edit().clear().apply()
        _profile.value = Profile()
        _settings.value = loadSettings()
    }

    private companion object {
        const val KEY_PROFILE = "profile"
        const val KEY_API = "claude_api_key"
        const val KEY_GH = "greenhouse_boards"
        const val KEY_LEVER = "lever_companies"
        const val KEY_ARBEITNOW = "use_arbeitnow"
    }
}

data class Settings(
    val claudeApiKey: String = "",
    val greenhouseBoards: List<String> = DEFAULT_GREENHOUSE,
    val leverCompanies: List<String> = DEFAULT_LEVER,
    val useArbeitnow: Boolean = true,
) {
    companion object {
        // Starting points with German offices; users edit these in Settings.
        // A board that doesn't exist (or moved ATS) is skipped silently.
        val DEFAULT_GREENHOUSE = listOf("n26", "getyourguide", "contentful", "hellofresh", "celonis", "deliveryhero")
        val DEFAULT_LEVER = listOf("scalable-capital", "enpal", "raisin")
    }
}
