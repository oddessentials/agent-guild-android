package com.oddessentials.agentguild

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText

fun ComposeTestRule.onAllNodesWithTagCount(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().size

fun ComposeTestRule.onAllNodesWithTextCount(text: String, substring: Boolean = false) =
    onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

/**
 * Clicks through the node's semantics action. Injected touches can be lost while the page's Yard
 * keeps the main thread busy drawing.
 */
fun SemanticsNodeInteraction.click(): SemanticsNodeInteraction = performSemanticsAction(SemanticsActions.OnClick)
