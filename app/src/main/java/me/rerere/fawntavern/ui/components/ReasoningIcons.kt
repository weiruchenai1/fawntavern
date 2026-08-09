package me.rerere.fawntavern.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import me.rerere.fawntavern.R
import me.rerere.fawntavern.data.api.ReasoningLevel

@Composable
fun reasoningIcon(level: ReasoningLevel): ImageVector = when (level) {
    ReasoningLevel.OFF -> ImageVector.vectorResource(R.drawable.reasoning_off)
    ReasoningLevel.AUTO, ReasoningLevel.MEDIUM -> ImageVector.vectorResource(R.drawable.reasoning_medium)
    ReasoningLevel.LOW -> ImageVector.vectorResource(R.drawable.reasoning_low)
    ReasoningLevel.HIGH -> ImageVector.vectorResource(R.drawable.reasoning_high)
    ReasoningLevel.XHIGH -> ImageVector.vectorResource(R.drawable.reasoning_xhigh)
}