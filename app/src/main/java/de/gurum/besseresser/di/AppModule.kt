package de.gurum.besseresser.di

import de.gurum.besseresser.data.ApiClient
import de.gurum.besseresser.data.AppDatabase
import de.gurum.besseresser.data.BackupManager
import de.gurum.besseresser.data.BesseresserApiService
import de.gurum.besseresser.data.DefaultHistoryRepository
import de.gurum.besseresser.data.DefaultProfileRepository
import de.gurum.besseresser.data.DefaultSettingsRepository
import de.gurum.besseresser.data.DefaultUiPrefsRepository
import de.gurum.besseresser.data.HistoryRepository
import de.gurum.besseresser.data.PhotoStorage
import de.gurum.besseresser.data.ProfileRepository
import de.gurum.besseresser.data.SettingsRepository
import de.gurum.besseresser.data.UiPrefsRepository
import de.gurum.besseresser.data.appDataStore
import de.gurum.besseresser.ui.AppStateViewModel
import de.gurum.besseresser.ui.HistoryViewModel
import de.gurum.besseresser.ui.MainViewModel
import de.gurum.besseresser.ui.ProfileViewModel
import de.gurum.besseresser.ui.SetupViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Zentrales Koin-Modul: Datenbank, DataStore, Repositories, API-Service und ViewModels. */
val appModule = module {

    single { AppDatabase.getInstance(androidContext()) }
    single { androidContext().appDataStore }
    single { PhotoStorage(androidContext()) }

    single<BesseresserApiService> { ApiClient }

    single<SettingsRepository> { DefaultSettingsRepository(get()) }
    single<ProfileRepository> { DefaultProfileRepository(get()) }
    single<UiPrefsRepository> { DefaultUiPrefsRepository(get()) }
    single<HistoryRepository> { DefaultHistoryRepository(androidContext(), get(), get()) }

    single { BackupManager(androidContext(), get(), get(), get(), get(), get()) }

    viewModel { AppStateViewModel(get(), get(), get()) }
    viewModel { MainViewModel(get(), get(), get()) }
    viewModel { HistoryViewModel(get(), get(), get()) }
    viewModel { SetupViewModel(get()) }
    viewModel { ProfileViewModel(get()) }
}
