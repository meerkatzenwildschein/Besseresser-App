package de.gurum.besseresser.di

import de.gurum.besseresser.data.AppDispatchers
import de.gurum.besseresser.data.ApiClient
import de.gurum.besseresser.data.AppDatabase
import de.gurum.besseresser.data.BackupManager
import de.gurum.besseresser.data.BackupService
import de.gurum.besseresser.data.BackupStorage
import de.gurum.besseresser.data.AndroidBackupStorage
import de.gurum.besseresser.data.BesseresserApiService
import de.gurum.besseresser.data.FazitManager
import de.gurum.besseresser.data.DefaultHistoryRepository
import de.gurum.besseresser.data.DefaultProfileRepository
import de.gurum.besseresser.data.DefaultSettingsRepository
import de.gurum.besseresser.data.DefaultUiPrefsRepository
import de.gurum.besseresser.data.HistoryRepository
import de.gurum.besseresser.data.AndroidImagePreprocessor
import de.gurum.besseresser.data.DefaultPhotoCaptureManager
import de.gurum.besseresser.data.ImagePreprocessor
import de.gurum.besseresser.data.PhotoCaptureManager
import de.gurum.besseresser.data.PhotoStorage
import de.gurum.besseresser.data.ProfileRepository
import de.gurum.besseresser.data.PromptRepository
import de.gurum.besseresser.data.DefaultPromptRepository
import de.gurum.besseresser.data.SettingsRepository
import de.gurum.besseresser.data.UiPrefsRepository
import de.gurum.besseresser.data.appDataStore
import de.gurum.besseresser.ui.AppStateViewModel
import de.gurum.besseresser.ui.ConfigViewModel
import de.gurum.besseresser.ui.HistoryDetailViewModel
import de.gurum.besseresser.ui.HistoryViewModel
import de.gurum.besseresser.ui.MainViewModel
import de.gurum.besseresser.ui.ProfileViewModel
import de.gurum.besseresser.ui.RecentNotesViewModel
import de.gurum.besseresser.ui.SetupViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Zentrales Koin-Modul: Datenbank, DataStore, Repositories, API-Service und ViewModels. */
val appModule = module {

    single { AppDispatchers() }

    single { AppDatabase.getInstance(androidContext()) }
    single { get<AppDatabase>().historyDao() }
    single { get<AppDatabase>().dayFazitDao() }
    single { get<AppDatabase>().weekFazitDao() }

    single { androidContext().appDataStore }
    single { PhotoStorage(androidContext()) }
    single<PhotoCaptureManager> { DefaultPhotoCaptureManager(androidContext()) }
    single<ImagePreprocessor> { AndroidImagePreprocessor(androidContext()) }

    single<BesseresserApiService> { ApiClient() }

    single<SettingsRepository> { DefaultSettingsRepository(get()) }
    single<ProfileRepository> { DefaultProfileRepository(get()) }
    single<PromptRepository> { DefaultPromptRepository(androidContext()) }
    single<UiPrefsRepository> { DefaultUiPrefsRepository(get()) }
    single<HistoryRepository> { DefaultHistoryRepository(get(), get(), get(), get()) }
    single { FazitManager(get(), get(), get(), getOrNull(), getOrNull(), get()) }

    single<BackupStorage> { AndroidBackupStorage(androidContext()) }
    single<BackupService> { BackupManager(get(), get(), get(), get(), get(), get()) }

    viewModel { AppStateViewModel(get(), get(), get(), get()) }
    viewModel { MainViewModel(get(), get(), get(), get(), get(), get(), savedStateHandle = get(), promptRepository = get()) }
    viewModel { HistoryViewModel(get(), get(), get(), get(), get(), get(), savedStateHandle = get(), profileRepository = get(), promptRepository = get()) }
    viewModel { SetupViewModel(get(), get(), get(), savedStateHandle = get()) }
    viewModel { ProfileViewModel(get()) }
    viewModel { ConfigViewModel(get(), get()) }
    viewModel { RecentNotesViewModel(get()) }
    viewModel { (entryId: Long) -> HistoryDetailViewModel(entryId, get(), get(), get(), get()) }
}
