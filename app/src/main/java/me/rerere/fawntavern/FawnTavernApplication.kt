package me.rerere.fawntavern

import android.app.Application
import kotlinx.coroutines.runBlocking
import me.rerere.fawntavern.data.backup.AppBackup

class FawnTavernApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runBlocking { AppBackup.recoverInterruptedImport(this@FawnTavernApplication) }
    }
}
