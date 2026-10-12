package dev.nulltogeek.codexremote

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SyntaxHighlightingUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun everyLanguageRuleCompilesOnAndroidsRegexEngine() {
        for (name in listOf("kotlin", "python", "javascript", "typescript", "shell", "sql", "json", "yaml", "css",
            "rust", "go", "java", "c", "cpp", "csharp", "ruby", "swift", "html")) {
            Regex(syntaxPattern(name))
            assertTrue(syntaxTokens("create(42) \"quoted\"", name).isNotEmpty())
        }
        assertNull(compileSyntaxPattern("(?<broken>"))
    }
    @Test fun shellCommandBlockRendersBracedVariablesWithoutClosingTheScreen() {
        val command = "echo \${HOME} \${name:-fallback} \$HOME # comment"
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            RemoteTheme("dark", motion = false) { CodeBlock(command, "terminal", collapsible = false) }
        } }
        compose.onNodeWithText(command).assertIsDisplayed()
        compose.onNodeWithText("Copy code").performClick()
        compose.onNodeWithText("Copied").assertIsDisplayed()
    }
}
