package me.rerere.stapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ALargeSmall
import me.rerere.stapp.R
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleHelp
import com.composables.icons.lucide.Database
import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Moon
import com.composables.icons.lucide.Package
import com.composables.icons.lucide.Route
import com.composables.icons.lucide.ScrollText
import com.composables.icons.lucide.Smile
import com.composables.icons.lucide.SlidersHorizontal
import com.composables.icons.lucide.SquareLibrary
import com.composables.icons.lucide.Sun
import me.rerere.stapp.data.settings.LanguageStore
import me.rerere.stapp.data.settings.ThemeMode
import me.rerere.stapp.ui.components.AppTopBar

private val LANGUAGE_CODES = listOf("zh", "en")

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    onNavigateToPresets: () -> Unit = {},
    onNavigateToCharacters: () -> Unit = {},
    onNavigateToWorldBooks: () -> Unit = {},
    onNavigateToWiSettings: () -> Unit = {},
    onNavigateToApiConfig: () -> Unit = {},
    onNavigateToDataManagement: () -> Unit = {},
    onNavigateToFontSize: () -> Unit = {},
    onNavigateToPromptLog: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var currentLang by remember { mutableStateOf(LanguageStore.getLanguage(context)) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLangDialog by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(stringResource(R.string.color_mode)) },
            text = {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(
                        Triple(ThemeMode.SYSTEM, stringResource(R.string.follow_system), Lucide.Monitor),
                        Triple(ThemeMode.LIGHT, stringResource(R.string.light), Lucide.Sun),
                        Triple(ThemeMode.DARK, stringResource(R.string.dark), Lucide.Moon),
                    ).forEach { (mode, label, icon) ->
                        val sel = mode == themeMode
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                            else MaterialTheme.colorScheme.surface)
                                .clickable {
                                    onThemeModeChange(mode)
                                    showThemeDialog = false
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(icon, null, Modifier.size(22.dp),
                                tint = if (sel) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Text(label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (sel) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f))
                            if (sel) {
                                Icon(Lucide.Check, null, Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {},
        )
    }

    if (showLangDialog) {
        AlertDialog(
            onDismissRequest = { showLangDialog = false },
            title = { Text(stringResource(R.string.language)) },
            text = {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    LANGUAGE_CODES.forEach { code ->
                        val label = LanguageStore.getLabel(code)
                        val sel = code == currentLang
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                            else MaterialTheme.colorScheme.surface)
                                .clickable {
                                    currentLang = code
                                    LanguageStore.setLanguage(context, code)
                                    LanguageStore.markPendingChange(context)
                                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
                                    showLangDialog = false
                                    (context as? Activity)?.let { act ->
                                        act.recreate()
                                        act.overridePendingTransition(0, 0)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (sel) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f))
                            if (sel) {
                                Icon(Lucide.Check, null, Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {},
        )
    }

    val themeLabel = when (themeMode) {
        ThemeMode.SYSTEM -> stringResource(R.string.follow_system)
        ThemeMode.LIGHT -> stringResource(R.string.light)
        ThemeMode.DARK -> stringResource(R.string.dark)
    }
    val currentThemeIcon = when (themeMode) {
        ThemeMode.SYSTEM -> Lucide.Monitor
        ThemeMode.LIGHT -> Lucide.Sun
        ThemeMode.DARK -> Lucide.Moon
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(stringResource(R.string.settings), onBack) }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection(stringResource(R.string.general_settings)) {
                SettingsRow(
                    { Icon(currentThemeIcon, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    stringResource(R.string.color_mode), themeLabel,
                    onClick = { showThemeDialog = true })
                SettingsRow(
                    { Icon(Lucide.Globe, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    stringResource(R.string.language), LanguageStore.getLabel(currentLang),
                    onClick = { showLangDialog = true })
                SettingsRow(
                    { Icon(Lucide.ALargeSmall, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    stringResource(R.string.font_size), onClick = onNavigateToFontSize)
                SettingsRow(
                    { Icon(Lucide.ScrollText, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    stringResource(R.string.debug_log), onClick = onNavigateToPromptLog)
            }
            SettingsSection(stringResource(R.string.workspace)) {
                SettingsRow(
                    { Icon(Lucide.Smile, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    stringResource(R.string.characters), onClick = onNavigateToCharacters)
                SettingsRow(
                    { Icon(Lucide.Route, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    stringResource(R.string.presets), onClick = onNavigateToPresets)
                SettingsRow(
                    { Icon(Lucide.SquareLibrary, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    stringResource(R.string.world_books), onClick = onNavigateToWorldBooks)
                SettingsRow(
                    { Icon(Lucide.SlidersHorizontal, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    stringResource(R.string.wi_activation_settings), onClick = onNavigateToWiSettings)
                SettingsRow(
                    { Icon(Lucide.Package, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    stringResource(R.string.api_config), onClick = onNavigateToApiConfig)
                SettingsRow(
                    { Icon(Lucide.Database, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    stringResource(R.string.data_management), onClick = onNavigateToDataManagement)
            }
            SettingsSection(stringResource(R.string.about)) {
                SettingsRow(
                    { Icon(Lucide.Info, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    stringResource(R.string.check_update), "0.0.1")
                SettingsRow(
                    { Icon(Lucide.CircleHelp, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    stringResource(R.string.about))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String? = null,
    onClick: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            icon()
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (value != null)
                Text(value, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Lucide.ChevronRight, null, Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
