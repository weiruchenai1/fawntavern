package me.rerere.fawntavern.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Github
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Scale
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Tag
import me.rerere.fawntavern.R
import me.rerere.fawntavern.ui.components.SettingsSubPage

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.1"
        } catch (_: Exception) { "0.0.1" }
    }
    val deviceName = remember {
        val manufacturer = Build.MANUFACTURER.trim()
        val model = Build.MODEL.trim()
        if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim()
        }
    }
    val systemInfo = stringResource(
        R.string.about_system_value,
        deviceName,
        Build.VERSION.RELEASE,
        Build.VERSION.SDK_INT,
    )

    BackHandler(onBack = onBack)

    SettingsSubPage(stringResource(R.string.about), onBack, horizontalAlignment = Alignment.CenterHorizontally) {
        // ── 品牌图标 ──
        Image(
            painter = painterResource(R.drawable.brand_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp)),
            contentScale = ContentScale.Crop,
        )

        // ── 应用名称 & 版本 ──
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        PrefSection(stringResource(R.string.about_information)) {
            AboutInfoRow(
                icon = Lucide.Tag,
                label = stringResource(R.string.about_version_label),
                value = versionName,
            )
            AboutInfoRow(
                icon = Lucide.Smartphone,
                label = stringResource(R.string.about_system),
                value = systemInfo,
            )
            AboutInfoRow(
                icon = Lucide.Github,
                label = stringResource(R.string.about_github),
                value = stringResource(R.string.about_github_value),
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
                    }
                },
            )
            AboutInfoRow(
                icon = Lucide.Scale,
                label = stringResource(R.string.about_license),
                value = stringResource(R.string.about_license_value),
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LICENSE_URL)))
                    }
                },
            )
        }
    }
}

private const val GITHUB_URL = "https://github.com/weiruchenai1/fawntavern"
private const val LICENSE_URL = "https://github.com/weiruchenai1/fawntavern/blob/main/LICENSE"

@Composable
private fun AboutInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null) {
            Icon(Lucide.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
