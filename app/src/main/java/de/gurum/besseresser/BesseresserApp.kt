package de.gurum.besseresser

import android.app.Application
import de.gurum.besseresser.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/** Application-Einstiegspunkt: startet den Koin-DI-Container. */
class BesseresserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@BesseresserApp)
            modules(appModule)
        }
    }
}
