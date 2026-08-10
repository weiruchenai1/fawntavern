package me.rerere.fawntavern.ui.chat

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.fawntavern.R
import com.composables.icons.lucide.Bolt
import com.composables.icons.lucide.ImagePlus
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PencilLine
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Smile
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.UserPen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.fawntavern.data.chat.ChatSession
import me.rerere.fawntavern.data.settings.UserProfileStore
import java.io.File
import java.io.FileOutputStream
import me.rerere.fawntavern.ui.components.Space4
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16
import me.rerere.fawntavern.ui.components.AppIconButton
import me.rerere.fawntavern.ui.components.appClickable

/** 根据当前小时返回对应的问候语字符串资源 ID */
private fun greetingResId(): Int {
    val hour = java.time.LocalTime.now().hour
    return when (hour) {
        in 0..5, in 22..23 -> R.string.greeting_night
        in 6..8 -> R.string.greeting_morning
        in 9..11 -> R.string.greeting_late_morning
        in 12..13 -> R.string.greeting_noon
        in 14..17 -> R.string.greeting_afternoon
        else -> R.string.greeting_evening  // 18-21
    }
}

/** 时间戳 → 本地日期（分组用） */
private fun toLocalDate(ts: Long): java.time.LocalDate? = try {
    java.time.Instant.ofEpochMilli(ts)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
} catch (_: Exception) { null }

/** 日期分组头：今天 / 昨天 / x月x日 */
private fun dateHeaderText(date: java.time.LocalDate, today: java.time.LocalDate, ctx: android.content.Context): String = when (date) {
    today -> ctx.getString(R.string.today)
    today.minusDays(1) -> ctx.getString(R.string.yesterday)
    else -> ctx.getString(R.string.date_header_fmt, date.monthValue, date.dayOfMonth)
}

@Composable
fun ChatDrawerContent(
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onCharSelect: () -> Unit = {},
    onCharList: () -> Unit = {},
    onSearch: () -> Unit = {},
    charName: String = "",
    charImage: android.graphics.Bitmap? = null,
    sessions: List<ChatSession> = emptyList(),
    currentSessionId: String? = null,
    onOpenSession: (String) -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    showChatListDate: Boolean = false,
    longPressHaptic: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var userName by remember { mutableStateOf(UserProfileStore.getName(context)) }
    var personaDescription by remember { mutableStateOf(UserProfileStore.getDescription(context)) }
    var avatarColor by remember {
        mutableStateOf(Color(UserProfileStore.getAvatarColor(context).toULong()))
    }
    var avatarBitmap by remember { mutableStateOf(loadAvatarBitmap(context)) }
    var showAvatarDialog by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: return@withContext
                        val bytes = input.readBytes()
                        input.close()
                        val avatarsDir = File(context.filesDir, "avatars").also { it.mkdirs() }
                        val destFile = File(avatarsDir, "user_avatar")
                        FileOutputStream(destFile).use { it.write(bytes) }
                        UserProfileStore.setAvatarPath(context, destFile.absolutePath)
                        withContext(Dispatchers.Main) {
                            avatarBitmap = loadAvatarBitmap(context)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    if (showAvatarDialog) {
        AlertDialog(
            onDismissRequest = { showAvatarDialog = false },
            title = { Text(stringResource(R.string.edit_avatar)) },
            text = {
                Column(Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Space12)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space12)) {
                        AvatarCircle(
                            bitmap = avatarBitmap,
                            color = avatarColor,
                            icon = Lucide.UserPen,
                            size = 48.dp,
                        )
                        Text(userName.ifBlank { "user" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }

                    OutlinedButton(
                        onClick = { imagePicker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Lucide.ImagePlus, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Space8))
                        Text(stringResource(R.string.pick_from_gallery))
                    }

                    if (avatarBitmap != null) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        UserProfileStore.setAvatarPath(context, null)
                                        File(context.filesDir, "avatars/user_avatar").delete()
                                    }
                                    avatarBitmap = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Lucide.Trash2, null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(Space8))
                            Text(stringResource(R.string.remove_avatar),
                                color = MaterialTheme.colorScheme.error)
                        }
                    }

                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAvatarDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    if (showNameDialog) {
        var editName by remember { mutableStateOf(userName) }
        var editDesc by remember { mutableStateOf(personaDescription) }
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text(stringResource(R.string.edit_username)) },
            text = {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Space12),
                ) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text(stringResource(R.string.edit_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editDesc,
                        onValueChange = { editDesc = it },
                        label = { Text(stringResource(R.string.persona_description_label)) },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = editName.trim()
                    if (trimmed.isNotBlank()) {
                        userName = trimmed
                        UserProfileStore.setName(context, trimmed)
                    }
                    personaDescription = editDesc.trim()
                    UserProfileStore.setDescription(context, personaDescription)
                    showNameDialog = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Column(
        Modifier.width(300.dp).fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .statusBarsPadding()
            .padding(Space16),
        verticalArrangement = Arrangement.spacedBy(Space16),
    ) {
        // ── 用户资料 ──
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { showAvatarDialog = true }) {
                AvatarCircle(bitmap = avatarBitmap, color = avatarColor,
                    icon = Lucide.UserPen, size = 40.dp)
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space4),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(userName.ifBlank { "user" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    AppIconButton(
                        icon = Lucide.PencilLine,
                        contentDescription = stringResource(R.string.edit),
                        onClick = { showNameDialog = true },
                        size = 32.dp,
                        iconSize = 16.dp,
                    )
                }
                Text(stringResource(greetingResId()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // ── 搜索入口 ──
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable { onSearch() }
                .padding(horizontal = Space12, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(Space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.Search, null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.search_chats),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ── 聊天历史 ──
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space4)) {
            item {
                Text(stringResource(R.string.chat_history),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Space4))
            }
            if (sessions.isEmpty()) {
                item {
                    Text(stringResource(R.string.no_chats),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp))
                }
            } else if (showChatListDate) {
                // 按日期分组：今天 / 昨天 / x月x日 作为组头显示在标题卡片之外，卡片内不再放时间
                val today = java.time.LocalDate.now()
                val sorted = sessions.sortedByDescending { it.updatedAt }
                var lastDate: java.time.LocalDate? = null
                sorted.forEach { s ->
                    val date = toLocalDate(s.updatedAt)
                    if (date != null && date != lastDate) {
                        item(key = "date_$date") {
                            Text(dateHeaderText(date, today, context),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Space8, bottom = Space4))
                        }
                        lastDate = date
                    }
                    item(key = s.id) { SessionCard(s, currentSessionId, onOpenSession, onDeleteSession, longPressHaptic) }
                }
            } else {
                itemsIndexed(sessions, key = { _, s -> s.id }) { _, s ->
                    SessionCard(s, currentSessionId, onOpenSession, onDeleteSession, longPressHaptic)
                }
            }
        }

        // ── 当前角色 ──
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable { onCharSelect() }
                .padding(horizontal = Space12, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(Space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 角色卡图片（编辑器可更换）；无图回退到笑脸图标
            if (charImage != null) {
                Image(
                    bitmap = charImage.asImageBitmap(),
                    contentDescription = stringResource(R.string.character_avatar),
                    modifier = Modifier.size(32.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(Lucide.Smile, null, Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = charName.ifBlank { stringResource(R.string.default_character) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }

        // ── 底部导航 ──
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(
                icon = Lucide.Smile,
                contentDescription = stringResource(R.string.characters),
                onClick = onCharList,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            AppIconButton(
                icon = Lucide.Bolt,
                contentDescription = stringResource(R.string.settings),
                onClick = onSettings,
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}

/** 单条会话卡片：标题（+ 可选日期组头在卡片外，卡片内不含时间） */
@Composable
private fun SessionCard(
    s: ChatSession,
    currentSessionId: String?,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    longPressHaptic: Boolean,
) {
    val selected = s.id == currentSessionId
    val fallbackTitle = stringResource(R.string.new_chat)
    // 标题：优先用模型生成的 title，回退到首条用户消息预览
    val title = s.title.takeIf { it.isNotBlank() }
        ?: s.messages.firstOrNull { it.role == "user" }?.content
            ?.replace('\n', ' ')?.take(24)
        ?: fallbackTitle
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent
            )
            .appClickable(
                onClick = { onOpenSession(s.id) },
                onLongClick = { onDeleteSession(s.id) },
                hapticFeedbackEnabled = longPressHaptic,
            )
            .padding(horizontal = Space8, vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AvatarCircle(
    bitmap: android.graphics.Bitmap?,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    size: androidx.compose.ui.unit.Dp,
) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "avatar",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(icon, null, Modifier.size(size * 0.6f),
                tint = Color.White)
        }
    }
}

private fun loadAvatarBitmap(context: Context): android.graphics.Bitmap? {
    val path = UserProfileStore.getAvatarPath(context) ?: return null
    val file = File(path)
    if (!file.exists()) return null
    return try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
}
