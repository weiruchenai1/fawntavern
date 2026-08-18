package me.rerere.fawntavern.ui.privacy

import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.fawntavern.ui.components.AppTopBar

@Composable
fun PrivacyDocumentScreen(
    document: PrivacyDocument,
    onBack: () -> Unit,
) {
    val language = LocalConfiguration.current.locales[0].language
    val assetDirectory = if (language == "en") "privacy/en" else "privacy"
    val assetUrl = "file:///android_asset/$assetDirectory/${document.assetFileName}"
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(stringResource(document.titleRes), onBack) },
    ) { padding ->
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            factory = { context ->
                val view = WebView(context).apply {
                    setBackgroundColor(Color.WHITE)
                    settings.javaScriptEnabled = false
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = false
                    webViewClient = WebViewClient()
                    loadUrl(assetUrl)
                }
                webView = view
                view
            },
            update = { view ->
                if (view.url != assetUrl) view.loadUrl(assetUrl)
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
        }
    }
}
