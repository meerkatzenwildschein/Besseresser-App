package de.gurum.besseresser

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import de.gurum.besseresser.data.AppDatabase
import de.gurum.besseresser.data.BackupService
import de.gurum.besseresser.data.BesseresserApiService
import de.gurum.besseresser.data.DayFazitDao
import de.gurum.besseresser.data.HistoryDao
import de.gurum.besseresser.data.HistoryRepository
import de.gurum.besseresser.data.ImagePreprocessor
import de.gurum.besseresser.data.PhotoCaptureManager
import de.gurum.besseresser.data.PhotoStorage
import de.gurum.besseresser.data.ProfileRepository
import de.gurum.besseresser.data.SettingsRepository
import de.gurum.besseresser.data.UiPrefsRepository
import de.gurum.besseresser.data.WeekFazitDao
import de.gurum.besseresser.di.appModule
import de.gurum.besseresser.di.appModules
import de.gurum.besseresser.di.databaseModule
import de.gurum.besseresser.di.dispatchersModule
import de.gurum.besseresser.di.networkModule
import de.gurum.besseresser.di.repositoryModule
import de.gurum.besseresser.di.storageModule
import de.gurum.besseresser.di.viewModelModule
import de.gurum.besseresser.ui.AppStateViewModel
import de.gurum.besseresser.ui.ConfigViewModel
import de.gurum.besseresser.ui.HistoryDetailViewModel
import de.gurum.besseresser.ui.HistoryViewModel
import de.gurum.besseresser.ui.MainViewModel
import de.gurum.besseresser.ui.ProfileViewModel
import de.gurum.besseresser.ui.RecentNotesViewModel
import de.gurum.besseresser.ui.SetupViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.get

@OptIn(ExperimentalCoroutinesApi::class)
class KoinDiTest : KoinTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        stopKoin()
    }

    @After
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun testKoinModuleDefinitionsAndResolutions() {
        val testModule = module {
            single<SettingsRepository> { FakeSettingsRepository() }
            single<ProfileRepository> { FakeProfileRepository() }
            single<UiPrefsRepository> { FakeUiPrefsRepository() }
            single<HistoryRepository> { FakeHistoryRepository() }
            single<BackupService> { FakeBackupService() }
            single<BesseresserApiService> { FakeApiService() }
            single<ImagePreprocessor> { FakeImagePreprocessor() }
            single<PhotoCaptureManager> { FakePhotoCaptureManager() }
            single { de.gurum.besseresser.data.AppDispatchers() }

            factory { AppStateViewModel(get(), get(), get(), get()) }
            factory { MainViewModel(get(), get(), get(), get(), get(), get()) }
            factory { HistoryViewModel(get(), get(), get(), get(), get(), get()) }
            factory { SetupViewModel(get(), get(), get()) }
            factory { ProfileViewModel(get()) }
            factory { ConfigViewModel(get(), get()) }
            factory { RecentNotesViewModel(get()) }
            factory { (entryId: Long) -> HistoryDetailViewModel(entryId, get(), get(), get(), get()) }
        }

        startKoin {
            modules(testModule)
        }

        assertNotNull(get<SettingsRepository>())
        assertNotNull(get<ProfileRepository>())
        assertNotNull(get<UiPrefsRepository>())
        assertNotNull(get<HistoryRepository>())
        assertNotNull(get<BackupService>())
        assertNotNull(get<BesseresserApiService>())
        assertNotNull(get<ImagePreprocessor>())
        assertNotNull(get<PhotoCaptureManager>())

        assertNotNull(get<AppStateViewModel>())
        assertNotNull(get<MainViewModel>())
        assertNotNull(get<HistoryViewModel>())
        assertNotNull(get<SetupViewModel>())
        assertNotNull(get<ProfileViewModel>())
        assertNotNull(get<ConfigViewModel>())
        assertNotNull(get<RecentNotesViewModel>())
        assertNotNull(get<HistoryDetailViewModel> { parametersOf(123L) })
    }

    @Test
    fun testModularKoinModuleStructure() {
        org.junit.Assert.assertEquals(6, appModules.size)
        org.junit.Assert.assertTrue(appModules.contains(dispatchersModule))
        org.junit.Assert.assertTrue(appModules.contains(databaseModule))
        org.junit.Assert.assertTrue(appModules.contains(networkModule))
        org.junit.Assert.assertTrue(appModules.contains(storageModule))
        org.junit.Assert.assertTrue(appModules.contains(repositoryModule))
        org.junit.Assert.assertTrue(appModules.contains(viewModelModule))
    }
}
