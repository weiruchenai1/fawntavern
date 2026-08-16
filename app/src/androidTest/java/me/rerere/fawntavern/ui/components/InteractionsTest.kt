package me.rerere.fawntavern.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InteractionsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun clearFocusOnTapOnlyClearsUnhandledPageTaps() {
        compose.setContent {
            Box(Modifier.fillMaxSize().clearFocusOnTap()) {
                BasicTextField(
                    value = "text",
                    onValueChange = {},
                    modifier = Modifier.size(120.dp, 48.dp).testTag("field"),
                )
                Box(
                    Modifier.align(Alignment.BottomEnd).size(48.dp).testTag("blank"),
                )
            }
        }

        compose.onNodeWithTag("field").performClick().assertIsFocused()
        compose.onNodeWithTag("field").performClick().assertIsFocused()
        compose.onNodeWithTag("blank").performTouchInput { click() }
        compose.onNodeWithTag("field").assertIsNotFocused()
    }
}
