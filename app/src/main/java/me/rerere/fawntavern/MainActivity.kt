package me.rerere.fawntavern

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import me.rerere.fawntavern.data.api.Http
import me.rerere.fawntavern.data.settings.LanguageStore
import me.rerere.fawntavern.data.settings.PreferencesStore
import me.rerere.fawntavern.data.settings.ThemeMode
import me.rerere.fawntavern.data.settings.ThemeStore
import me.rerere.fawntavern.ui.chat.ChatScreen
import me.rerere.fawntavern.ui.components.HapticGate
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
            // Coil 单例：SVG 解码器（品牌图标 assets/icons/*.svg）+ 网络加载（引用 favicon），
            // 网络请求复用全局 OkHttp 连接池
            setSingletonImageLoaderFactory { context ->
                ImageLoader.Builder(context)
                    .components {
                        add(SvgDecoder.Factory(scaleToDensity = true))
                        add(OkHttpNetworkFetcherFactory(callFactory = { Http.client }))
                    }
                    .build()
            }

            var themeMode by remember { mutableStateOf(ThemeStore.getMode(this)) }
            val initialPrefs = remember { PreferencesStore.get(this) }
            var solidBackground by remember { mutableStateOf(initialPrefs.solidBackground) }
            val app = application as FawnTavernApplication
            val recoveryState by app.recoveryState.collectAsState()
            FawnTavernTheme(themeMode = themeMode, solidBackground = solidBackground) {
                // 长按触觉闸门：按"长按触觉反馈"偏好过滤系统 LongPress 触觉（角色卡片/会话长按等）
                HapticGate {
                    when (val state = recoveryState) {
                        FawnTavernApplication.RecoveryState.Recovering -> RecoveryLoading()
                        FawnTavernApplication.RecoveryState.Ready -> ChatScreen(
                            themeMode = themeMode,
                            onThemeModeChange = { mode ->
                                themeMode = mode
                                ThemeStore.setMode(this@MainActivity, mode)
                            },
                            solidBackground = solidBackground,
                            onSolidBackgroundChange = { value ->
                                solidBackground = value
                                PreferencesStore.update(this@MainActivity) { it.copy(solidBackground = value) }
                            },
                            startAtSettings = startAtSettings,
                        )
                        is FawnTavernApplication.RecoveryState.Failed -> RecoveryFailure(
                            message = state.error.message.orEmpty(),
                            onRetry = app::retryRecovery,
                        )
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RecoveryLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.backup_recovery_in_progress),
            modifier = Modifier.padding(top = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@androidx.compose.runtime.Composable
private fun RecoveryFailure(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.backup_recovery_failed_fmt, message),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.retry))
        }
    }
}
