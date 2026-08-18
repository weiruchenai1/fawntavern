package me.rerere.fawntavern.ui.privacy

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import me.rerere.fawntavern.R

private val AgreementBlue = Color(0xFF1976D2)

@Composable
fun PrivacyConsentScreen(
    onSkip: () -> Unit,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    onOpenDocument: (PrivacyDocument) -> Unit,
) {
    var checked by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.privacy_tagline),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.privacy_skip), color = AgreementBlue)
            }
        }

        Spacer(Modifier.weight(1f))
        BrandBlock(logoSize = 88.dp)
        Spacer(Modifier.weight(1f))

        ConsentButtons(
            checked = checked,
            onAgree = onAgree,
            onDecline = onDecline,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        ConsentAcknowledgement(
            checked = checked,
            onCheckedChange = { checked = it },
            onOpenDocument = onOpenDocument,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PrivacyConsentBottomSheet(
    onDismiss: () -> Unit,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    onOpenDocument: (PrivacyDocument) -> Unit,
) {
    var checked by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.privacy_tagline),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            BrandBlock(logoSize = 88.dp)
            Spacer(Modifier.height(20.dp))
            ConsentButtons(checked, onAgree, onDecline)
            ConsentAcknowledgement(
                checked = checked,
                onCheckedChange = { checked = it },
                onOpenDocument = onOpenDocument,
                modifier = Modifier.padding(vertical = 18.dp),
            )
        }
    }
}

@Composable
private fun BrandBlock(logoSize: androidx.compose.ui.unit.Dp) {
    Image(
        painter = painterResource(R.drawable.brand_logo_round),
        contentDescription = stringResource(R.string.app_name),
        modifier = Modifier
            .size(logoSize)
            .clip(CircleShape),
    )
    Text(
        text = stringResource(R.string.app_name),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ConsentButtons(
    checked: Boolean,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onDecline,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
        ) {
            Text(stringResource(R.string.privacy_consent_decline))
        }
        Button(
            onClick = onAgree,
            enabled = checked,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
        ) {
            Text(stringResource(R.string.privacy_consent_agree))
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ConsentAcknowledgement(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onOpenDocument: (PrivacyDocument) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefix = stringResource(R.string.privacy_consent_prefix)
    val conjunction = stringResource(R.string.privacy_consent_and)
    val agreement = stringResource(R.string.user_agreement_title)
    val privacy = stringResource(R.string.privacy_policy_title)
    val annotated = buildAnnotatedString {
        append(prefix)
        pushStringAnnotation("document", PrivacyDocument.USER_AGREEMENT.name)
        withStyle(SpanStyle(color = AgreementBlue)) { append(agreement) }
        pop()
        append(conjunction)
        pushStringAnnotation("document", PrivacyDocument.PRIVACY_POLICY.name)
        withStyle(SpanStyle(color = AgreementBlue)) { append(privacy) }
        pop()
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(1.5.dp, if (checked) AgreementBlue else MaterialTheme.colorScheme.outline, CircleShape)
                .clickable { onCheckedChange(!checked) },
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Lucide.Check,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = AgreementBlue,
                )
            }
        }
        ClickableText(
            text = annotated,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            onClick = { offset ->
                annotated.getStringAnnotations("document", offset, offset)
                    .firstOrNull()
                    ?.item
                    ?.let { onOpenDocument(PrivacyDocument.valueOf(it)) }
            },
        )
    }
}
