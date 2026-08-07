package me.rerere.fawntavern

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
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.svg.SvgDecoder
import me.rerere.fawntavern.data.settings.LanguageStore
import me.rerere.fawntavern.data.settings.ThemeMode
import me.rerere.fawntavern.data.settings.ThemeStore
import me.rerere.fawntavern.ui.chat.ChatScreen
import me.rerere.fawntavern.ui.theme.FawnTavernTheme
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
            // Coil 单例注册 SVG 解码器（品牌图标 assets/icons/*.svg）
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context)
                    .components {
                        add(SvgDecoder.Factory(scaleToDensity = true))
                    }
                    .build()
            }

            var themeMode by remember { mutableStateOf(ThemeStore.getMode(this)) }
            FawnTavernTheme(themeMode = themeMode) {
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
