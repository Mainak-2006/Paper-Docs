package com.company.paperdocs

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.test.filters.LargeTest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val editorText = "An actual native editor draft.\nSaved across recreation."

    // Unique per run: the emulator keeps app data between installs, so an older run may still
    // hold a document with the same name and the search would then match two cards.
    private val title = "UI integration ${System.currentTimeMillis()}"

    @Test fun libraryStopsLoadingOnceDocumentsAreReady() {
        compose.waitUntil(15_000) { compose.activityRule.scenario.state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
        // A permanently-suspending collector inside the init block used to leave this true forever.
        compose.waitUntil(15_000) {
            compose.onAllNodes(isLoadingIndicator()).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Recent documents").assertExists()
    }

    @Test fun documentCanBeWrittenRenamedReopenedAndRecreated() {
        compose.waitUntil(15_000) { compose.activityRule.scenario.state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
        compose.onNodeWithText("Blank", useUnmergedTree = true).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Back to documents").fetchSemanticsNodes().isNotEmpty() }
        // The title sits inside a clickable column; wait for it explicitly because a signed-in
        // account makes the editor do a Firestore round trip on open.
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Untitled document").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Untitled document").performClick()
        // Clicking the title opens the "Rename document" dialog, whose OutlinedTextField carries the
        // current title. That dialog is a separate composition, so wait for the editable node rather
        // than assuming it is there on the next line.
        try {
            compose.waitUntil(20_000) {
                compose.onAllNodes(hasSetTextAction() and hasText("Untitled document")).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: ComposeTimeoutException) {
            // A bare "could not find node" says nothing about what was on screen, which makes this
            // failure undiagnosable without rerunning. Report the editable nodes that did exist.
            val editable = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().map { node ->
                node.config.getOrNull(SemanticsProperties.EditableText)?.text
                    ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    ?: "(no text)"
            }
            val dialogShown = compose.onAllNodesWithText("Rename document").fetchSemanticsNodes().isNotEmpty()
            throw AssertionError(
                "Timed out waiting for the editable title. " +
                    "Rename dialog showing: $dialogShown. Editable nodes present: $editable"
            )
        }
        compose.onNode(hasSetTextAction() and hasText("Untitled document")).performTextReplacement(title)
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Rename document").fetchSemanticsNodes().isEmpty() }
        onView(withContentDescription("Document body")).perform(replaceText(editorText))
        closeSoftKeyboard()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("  Saved on this device").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back to documents").performClick()
        // Same race as the title field above: the search field is re-composed on navigation, so
        // wait for the editable node rather than assuming it is already there.
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasSetTextAction() and hasText("Search your documents")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction() and hasText("Search your documents")).performTextInput(title)
        closeSoftKeyboard()
        val documentCard = hasText(title) and hasClickAction() and !hasSetTextAction()
        compose.waitUntil(20_000) { compose.onAllNodes(documentCard).fetchSemanticsNodes().size == 1 }
        compose.onNode(documentCard).performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Back to documents").fetchSemanticsNodes().isNotEmpty() }
        onView(withContentDescription("Document body")).check(matches(isDisplayed())).check(matches(withText(editorText)))
        compose.activityRule.scenario.recreate()
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Back to documents").fetchSemanticsNodes().isNotEmpty() }
        onView(withContentDescription("Document body")).check(matches(isDisplayed())).check(matches(withText(editorText)))
    }
}

/** Material3 circular progress indicators expose this only through their progress semantics. */
private fun isLoadingIndicator() =
    SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
