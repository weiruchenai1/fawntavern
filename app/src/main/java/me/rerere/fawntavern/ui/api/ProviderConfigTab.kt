package me.rerere.fawntavern.ui.api

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PlugZap
import com.composables.icons.lucide.Trash2
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ApiProvider
import me.rerere.fawntavern.data.api.GradioImageProfile
import me.rerere.fawntavern.ui.components.ConfirmDeleteDialog
import me.rerere.fawntavern.ui.components.Space8
import me.rerere.fawntavern.ui.components.Space16

private const val HF_TOKEN_URL = "https://huggingface.co/settings/tokens"

private val API_TYPES = listOf(
    "openai" to "OpenAI",
    "google" to "Google",
    "claude" to "Claude",
    "gradio" to "Gradio",
)

@Composable
internal fun ProviderConfigTab(
    prov: ApiProvider,
    update: (ApiProvider) -> Unit,
    onSave: (ApiProvider) -> Unit,
    onDelete: () -> Unit,
    isNew: Boolean,
    modifier: Modifier = Modifier,
) {
    var keyVisible by remember { mutableStateOf(false) }
    var name by remember(prov) { mutableStateOf(prov.name) }
    var baseUrl by remember(prov) { mutableStateOf(prov.baseUrl) }
    var customApiPath by remember(prov) { mutableStateOf(prov.apiPath) }
    var chatApiPath by remember(prov) {
        mutableStateOf(if (prov.type == "openai") {
            prov.chatApiPath.ifBlank { "/chat/completions" }
        } else prov.chatApiPath)
    }
    var responsesApiPath by remember(prov) {
        mutableStateOf(if (prov.type == "openai") {
            prov.responsesApiPath.ifBlank { "/responses" }
        } else prov.responsesApiPath)
    }
    var imageGenerationApiPath by remember(prov) {
        mutableStateOf(if (prov.type == "openai") {
            prov.imageGenerationApiPath.ifBlank { "/images/generations" }
        } else prov.imageGenerationApiPath)
    }
    var imageEditApiPath by remember(prov) {
        mutableStateOf(if (prov.type == "openai") {
            prov.imageEditApiPath.ifBlank { "/images/edits" }
        } else prov.imageEditApiPath)
    }
    var apiKey by remember(prov) { mutableStateOf(prov.apiKey) }
    var enabledValue by remember(prov) { mutableStateOf(prov.enabled) }
    var balanceEnabled by remember(prov) { mutableStateOf(prov.balanceEnabled) }
    var balancePath by remember(prov) { mutableStateOf(prov.balancePath) }
    var balanceJsonKey by remember(prov) { mutableStateOf(prov.balanceJsonKey) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showTestDialog by remember { mutableStateOf(false) }

    val currentProv = prov.copy(
        name = name,
        baseUrl = baseUrl,
        apiPath = if (prov.type == "openai") "" else customApiPath,
        chatApiPath = chatApiPath,
        responsesApiPath = responsesApiPath,
        imageGenerationApiPath = imageGenerationApiPath,
        imageEditApiPath = imageEditApiPath,
        apiKey = apiKey, enabled = enabledValue,
        useResponseApi = prov.useResponseApi,
        balanceEnabled = balanceEnabled, balancePath = balancePath, balanceJsonKey = balanceJsonKey,
    )
    val apiPath = when (prov.type) {
        "google" -> customApiPath.ifBlank {
            "/models/{model}:streamGenerateContent?alt=sse"
        }
        "claude" -> customApiPath.ifBlank { "/messages" }
        "gradio" -> customApiPath.ifBlank {
            if (prov.gradioImageProfile == GradioImageProfile.Z_IMAGE_OFFICIAL) {
                "/generate"
            } else {
                "/generate_image"
            }
        }
        else -> customApiPath
    }

    Column(
        modifier.fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(Space16),
        verticalArrangement = Arrangement.spacedBy(Space16),
    ) {
        val types = API_TYPES
        val typeIdx = types.indexOfFirst { it.first == prov.type }.coerceAtLeast(0)
        var selectedTypeIdx by remember(prov) { mutableIntStateOf(typeIdx) }
        val typeScrollState = rememberScrollState()
        val typeItemWidthPx = with(LocalDensity.current) { 104.dp.roundToPx() }
        LaunchedEffect(selectedTypeIdx, typeItemWidthPx) {
            typeScrollState.animateScrollTo(selectedTypeIdx * typeItemWidthPx)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(typeScrollState),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.width((types.size * 104).dp)) {
                types.forEachIndexed { idx, (key, label) ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(idx, types.size),
                        selected = selectedTypeIdx == idx,
                        onClick = {
                            selectedTypeIdx = idx
                            customApiPath = ""
                            chatApiPath = ""
                            responsesApiPath = ""
                            imageGenerationApiPath = ""
                            imageEditApiPath = ""
                            update(currentProv.copy(
                                type = key,
                                apiPath = "",
                                chatApiPath = "",
                                responsesApiPath = "",
                                imageGenerationApiPath = "",
                                imageEditApiPath = "",
                                useResponseApi = key == "openai" && prov.useResponseApi,
                            ))
                        },
                        label = { Text(label) },
                    )
                }
            }
        }

        OutlinedTextField(name, { name = it; update(currentProv.copy(name = it)) },
            label = { Text(stringResource(R.string.provider_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

        OutlinedTextField(apiKey, { apiKey = it; update(currentProv.copy(apiKey = it)) },
            label = { Text(stringResource(
                if (prov.type == "gradio") R.string.hf_token_label else R.string.api_key_label,
            )) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(if (keyVisible) Lucide.EyeOff else Lucide.Eye, null, Modifier.size(20.dp))
                }
            })

        if (prov.type == "gradio") {
            val hint = stringResource(R.string.hf_token_optional_hint)
            val getKey = stringResource(R.string.hf_token_get_key)
            val linkStyles = TextLinkStyles(
                style = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                ),
            )
            val annotated = buildAnnotatedString {
                append(hint)
                append(" ")
                val start = length
                append(getKey)
                addLink(LinkAnnotation.Url(HF_TOKEN_URL, styles = linkStyles), start, length)
            }
            Text(
                annotated,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(baseUrl, { baseUrl = it; update(currentProv.copy(baseUrl = it)) },
            label = { Text(stringResource(R.string.api_base_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (prov.type == "gradio") HF_Z_IMAGE_URL else "https://api.openai.com/v1") })

        if (prov.type == "openai") {
            OutlinedTextField(
                value = chatApiPath,
                onValueChange = { chatApiPath = it; update(currentProv.copy(chatApiPath = it)) },
                label = { Text(stringResource(R.string.chat_api_path_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = responsesApiPath,
                onValueChange = { responsesApiPath = it; update(currentProv.copy(responsesApiPath = it)) },
                label = { Text(stringResource(R.string.responses_api_path_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = imageGenerationApiPath,
                onValueChange = {
                    imageGenerationApiPath = it
                    update(currentProv.copy(imageGenerationApiPath = it))
                },
                label = { Text(stringResource(R.string.image_generation_api_path_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = imageEditApiPath,
                onValueChange = { imageEditApiPath = it; update(currentProv.copy(imageEditApiPath = it)) },
                label = { Text(stringResource(R.string.image_edit_api_path_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = apiPath,
                onValueChange = {
                    customApiPath = it
                    update(currentProv.copy(apiPath = it))
                },
                label = { Text(stringResource(R.string.api_path_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.is_enabled_label), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(enabledValue, { enabledValue = it; update(currentProv.copy(enabled = it)) })
        }

        if (prov.type != "gradio") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.balance_label), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(balanceEnabled, { balanceEnabled = it; update(currentProv.copy(balanceEnabled = it)) })
            }
        }

        if (balanceEnabled && prov.type != "gradio") {
            OutlinedTextField(balancePath, { balancePath = it; update(currentProv.copy(balancePath = it)) },
                label = { Text(stringResource(R.string.balance_api_path)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("/user/balance") })

            OutlinedTextField(balanceJsonKey, { balanceJsonKey = it; update(currentProv.copy(balanceJsonKey = it)) },
                label = { Text(stringResource(R.string.balance_json_key)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("balance_infos[0].total_balance") })
        }

        Spacer(Modifier.height(Space8))

        if (showTestDialog) {
            // 用当前编辑中的配置（含未保存改动）测试，模型取自 currentProv.models
            ConnectionTestDialog(prov = currentProv, onDismiss = { showTestDialog = false })
        }

        if (showDeleteConfirm) {
            ConfirmDeleteDialog(
                title = stringResource(R.string.delete_provider_title),
                text = stringResource(R.string.delete_provider_msg_fmt, currentProv.name),
                onConfirm = { showDeleteConfirm = false; onDelete() },
                onDismiss = { showDeleteConfirm = false },
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space8),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { showTestDialog = true }) {
                Icon(Lucide.PlugZap, stringResource(R.string.test_connection), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            if (!isNew) {
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Lucide.Trash2, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                }
            }
            Button(onClick = { onSave(currentProv) }) {
                Text(stringResource(R.string.save))
            }
        }
    }
}
