package de.gurum.besseresser.di

import de.gurum.besseresser.ApiClient
import de.gurum.besseresser.AppDatabase
import de.gurum.besseresser.AppStateViewModel
import de.gurum.besseresser.BackupManager
import de.gurum.besseresser.BesseresserApiService
import de.gurum.besseresser.DefaultHistoryRepository
import de.gurum.besseresser.DefaultProfileRepository
import de.gurum.besseresser.DefaultSettingsRepository
import de.gurum.besseresser.DefaultUiPrefsRepository
import de.gurum.besseresser.HistoryRepository
import de.gurum.besseresser.HistoryViewModel
import de.gurum.besseresser.MainViewModel
import de.gurum.besseresser.PhotoStorage
import de.gurum.besseresser.ProfileRepository
import de.gurum.besseresser.ProfileViewModel
import de.gurum.besseresser.SettingsRepository
import de.gurum.besseresser.SetupViewModel
import de.gurum.besseresser.UiPrefsRepository
import de.gurum.besseresser.appDataStore
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
