package me.rerere.fawntavern.ui.chat

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.SquarePen
import com.composables.icons.lucide.MessageCirclePlus
import com.composables.icons.lucide.Package
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.X
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ReasoningLevel
import me.rerere.fawntavern.extension.QuickReply
import me.rerere.fawntavern.ui.api.ProviderIcon
import me.rerere.fawntavern.ui.components.AppIconButton
import me.rerere.fawntavern.ui.components.reasoningIcon
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space12
import me.rerere.fawntavern.ui.components.Space16
import me.rerere.fawntavern.ui.components.noRippleClickable

@Composable
private fun KelivoDrawerIcon(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.list),
        contentDescription = stringResource(R.string.menu),
        modifier = modifier,
        tint = MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun ChatTopBar(title: String?, subtitle: String?, onDrawer: () -> Unit, onNewChat: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).statusBarsPadding().padding(vertical = Space8),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KelivoDrawerIcon(
            Modifier.noRippleClickable { onDrawer() }
                .padding(start = Space16, top = Space12, bottom = Space12, end = Space12)
                .size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(
            Lucide.MessageCirclePlus, "New Chat",
            Modifier.noRippleClickable { onNewChat() }
                .padding(start = Space12, top = Space12, bottom = Space12, end = Space16).size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun ChatBottomArea(
    state: TextFieldState,
    attachments: List<Attachment>,
    onRemoveAttachment: (Attachment) -> Unit,
    showAttachment: Boolean,
    onToggleAttachment: () -> Unit,
    editing: Boolean = false,
    onCancelEdit: () -> Unit = {},
    onExpand: () -> Unit = {},
    onSend: () -> Unit,
    currentModelId: String = "",
    reasoning: ReasoningLevel = ReasoningLevel.AUTO,
    imageGenerationEnabled: Boolean = false,
    generating: Boolean = false,
    searchEnabled: Boolean = false,
    searchProvider: String = "",
    builtInSearchEnabled: Boolean = false,
    onStop: () -> Unit = {},
    onSelectModel: () -> Unit = {},
    onSelectReasoning: () -> Unit = {},
    onOpenImageGenerationSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onCamera: () -> Unit = {},
    onGallery: () -> Unit = {},
    onFile: () -> Unit = {},
    quickReplies: List<QuickReply> = emptyList(),
    onQuickReply: (QuickReply) -> Unit = {},
    enterToSend: Boolean = true,
) {
    val hasContent = state.text.isNotBlank() || attachments.isNotEmpty()
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().padding(horizontal = Space16, vertical = Space8)) {
        // 快捷回复行（UI 插槽扩展）：位于输入卡片上方，可横向滚动
        if (quickReplies.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(bottom = Space8),
                horizontalArrangement = Arrangement.spacedBy(Space8),
            ) {
                itemsIndexed(quickReplies) { _, qr ->
                    Text(
                        qr.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onQuickReply(qr) }
                            .padding(horizontal = Space12, vertical = Space8),
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            // 编辑态：输入框内部最上方显示"编辑中"，右侧取消按钮 X
            if (editing) {
                Row(
                    Modifier.fillMaxWidth().padding(start = Space12, end = Space8, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.SquarePen, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.editing_in_progress), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Lucide.X, stringResource(R.string.cancel),
                        Modifier.size(32.dp).noRippleClickable { onCancelEdit() }.padding(8.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 附件预览：位于输入框上方，可横向滚动
            if (attachments.isNotEmpty()) {
                InputAttachmentRow(
                    attachments = attachments,
                    onRemove = onRemoveAttachment,
                    modifier = Modifier.padding(start = Space12, end = Space12, top = Space12),
                )
            }
            // 展开按钮与输入文本同级（同一行）：图标随占位文本垂直居中，左右间距与底部工具行一致
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    state = state,
                    textStyle = MaterialTheme.typography.titleSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    // 限高走 lineLimits 而非 heightIn：BTF2 在测量阶段自己滚动内部内容，
                    // 外部限高只会裁掉超出部分
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                    // 回车发送：软键盘用 Send 动作；硬件 Enter（非 Shift）在 modifier 里拦截为发送，Shift+Enter 保留换行
                    keyboardOptions = if (enterToSend) {
                        KeyboardOptions(imeAction = ImeAction.Send)
                    } else {
                        KeyboardOptions.Default
                    },
                    onKeyboardAction = if (enterToSend) ({ onSend() }) else null,
                    modifier = Modifier.weight(1f)
                        .padding(
                            start = Space12, end = Space8,
                            top = if (attachments.isEmpty()) 12.dp else 8.dp,
                            bottom = if (attachments.isEmpty()) 12.dp else 8.dp,
                        )
                        .heightIn(min = 40.dp)
                        .then(if (enterToSend) Modifier.onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp && event.key == Key.Enter && !event.isShiftPressed) {
                                onSend()
                                true
                            } else false
                        } else Modifier),
                    decorator = TextFieldDecorator { inner ->
                        // CenterStart：空态占位文本在字段内垂直居中，与右侧展开图标对齐
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            if (state.text.isEmpty()) {
                                Text(stringResource(R.string.input_hint), style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            inner()
                        }
                    },
                )
                // 内联操作小图标：透明容器点击显圆形按压背景，尺寸/右缘间距与底部工具行一致
                AppIconButton(
                    icon = Lucide.Maximize,
                    contentDescription = stringResource(R.string.expand),
                    onClick = onExpand,
                    modifier = Modifier.padding(end = Space12),
                    size = 36.dp,
                    iconSize = 24.dp,
                )
            }
            Row(Modifier.fillMaxWidth().padding(start = Space12, end = Space12, bottom = Space8), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                // 左侧工具图标组：统一 spacedBy(Space8) 间距，AppIconButton 点击时显示圆形按压背景
                Row(horizontalArrangement = Arrangement.spacedBy(Space8), verticalAlignment = Alignment.CenterVertically) {
                    // 模型选择器：只显示提供商图标（随所选模型变化）。
                    // AppIconButton 只接受固定 ImageVector，这里手工包一层圆形可点击区以承载 ProviderIcon
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).clickable { onSelectModel() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (currentModelId.isNotBlank()) {
                            ProviderIcon(currentModelId, size = 24.dp)
                        } else {
                            Icon(Lucide.Package, stringResource(R.string.select_model),
                                Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // 模型内置搜索开启时显示内置搜索图标；否则外部搜索开启时显示所选提供商图标，关闭时显示默认地球图标
                    if (builtInSearchEnabled) {
                        AppIconButton(
                            icon = Lucide.Earth,
                            contentDescription = stringResource(R.string.model_builtin_search),
                            onClick = onOpenSearch,
                            size = 36.dp,
                            iconSize = 24.dp,
                        )
                    } else if (searchEnabled && searchProvider.isNotBlank()) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).clickable { onOpenSearch() },
                            contentAlignment = Alignment.Center,
                        ) {
                            ProviderIcon(searchProvider, size = 24.dp)
                        }
                    } else {
                        AppIconButton(
                            icon = Lucide.Earth,
                            contentDescription = stringResource(R.string.web_search),
                            onClick = onOpenSearch,
                            size = 36.dp,
                            iconSize = 24.dp,
                        )
                    }
                    // 思考预算：灯泡光线数量即档位，着色与输入区其它图标一致
                    AppIconButton(
                        icon = reasoningIcon(reasoning),
                        contentDescription = stringResource(R.string.thinking_budget),
                        onClick = onSelectReasoning,
                        size = 36.dp,
                        iconSize = 24.dp,
                    )
                    if (imageGenerationEnabled) {
                        AppIconButton(
                            icon = Lucide.Image,
                            contentDescription = stringResource(R.string.image_generation_settings),
                            onClick = onOpenImageGenerationSettings,
                            size = 36.dp,
                            iconSize = 24.dp,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space8), verticalAlignment = Alignment.CenterVertically) {
                    // 附件展开/折叠（Plus ↔ X）
                    AppIconButton(
                        icon = if (showAttachment) Lucide.X else Lucide.Plus,
                        contentDescription = stringResource(
                            if (showAttachment) R.string.close else R.string.add,
                        ),
                        onClick = onToggleAttachment,
                        size = 36.dp,
                        iconSize = 24.dp,
                    )
                    Box(
                        Modifier.size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (generating || hasContent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                            .clickable { if (generating) onStop() else onSend() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (generating) Lucide.Square else Lucide.ArrowUp,
                            if (generating) stringResource(R.string.stop) else stringResource(R.string.send),
                            Modifier.size(if (generating) 16.dp else 22.dp),
                            tint = if (generating || hasContent) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        AnimatedVisibility(visible = showAttachment) {
            Column(Modifier.fillMaxWidth().padding(top = Space8), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.Center) {
                    AttachBtn(Lucide.Camera, stringResource(R.string.take_photo), onClick = onCamera); Spacer(Modifier.width(Space8))
                    AttachBtn(Lucide.Image, stringResource(R.string.gallery), onClick = onGallery); Spacer(Modifier.width(Space8))
                    AttachBtn(Lucide.Paperclip, stringResource(R.string.file), onClick = onFile)
                }
            }
        }
    }
}

@Composable
private fun AttachBtn(icon: ImageVector, label: String, onClick: () -> Unit = {}) {
    Column(Modifier.width(109.dp).height(80.dp).clip(RoundedCornerShape(Space8)).background(MaterialTheme.colorScheme.primaryContainer).clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.height(Space8))
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
