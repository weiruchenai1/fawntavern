package me.rerere.stapp

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat
import me.rerere.stapp.data.settings.LanguageStore
import me.rerere.stapp.data.settings.ThemeMode
import me.rerere.stapp.data.settings.ThemeStore
import me.rerere.stapp.ui.chat.ChatScreen
import me.rerere.stapp.ui.theme.STAppTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val lang = LanguageStore.getLanguage(newBase)
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration).apply {
            setLocale(locale)
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 同时设置 Application 级 locale（覆盖进程级资源，attachBaseContext 只影响 Activity 自身）
        val lang = LanguageStore.getLanguage(this)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 切换语言重启回来时直接重新打开设置页
        val startAtSettings = LanguageStore.consumePendingChange(this)

        setContent {
            var themeMode by remember { mutableStateOf(ThemeStore.getMode(this)) }
            STAppTheme(themeMode = themeMode) {
                ChatScreen(
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        ThemeStore.setMode(this@MainActivity, mode)
                    },
                    startAtSettings = startAtSettings,
                )
            }
        }
    }
}
