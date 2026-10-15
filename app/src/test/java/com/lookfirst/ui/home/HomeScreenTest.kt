package com.lookfirst.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.lookfirst.ui.theme.LookFirstTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsAppName() {
        composeRule.setContent { LookFirstTheme { HomeScreen() } }
        composeRule.onNodeWithText("LookFirst").assertExists()
    }
}
