package com.aimediasorter.app

import android.Manifest
import android.view.WindowInsets
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aimediasorter.app.data.preferences.GeminiKeyVault
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Real installed Compose controls and persistence; no live API calls or UI-state shortcuts. */
@RunWith(AndroidJUnit4::class)
class GeminiSettingsUiTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val vault=GeminiKeyVault(context)
    private val activity=InstalledActivityRule()
    private val compose=AndroidComposeTestRule(activity) { it.activity() }
    private val credentials=object: ExternalResource() {
        override fun before() {
            assumeFalse("Never overwrite a pre-existing personal key.",vault.hasKey())
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        }
        override fun after() { vault.clear() }
    }
    // The Activity is fully closed before the outer credential fixture is cleared.
    @get:Rule val rules: RuleChain=RuleChain.outerRule(credentials).around(compose)

    @Test fun authorizationKeyEnablesSavePersistsAcrossActivityRestartAndCanBeDeleted() {
        val synthetic="AQ."+"SyntheticCredential".repeat(8)
        val replacement="AQ."+"ReplacementCredential".repeat(9)
        fun openSettings() {
            compose.onNodeWithTag("nav-settings").performClick()
            compose.onNodeWithTag("settings-scroll").assertExists()
        }
        fun awaitKey(expected: String?) {
            try {
                compose.waitUntil(10_000) {
                    if(expected==null) !GeminiKeyVault(context).hasKey()
                    else runCatching { GeminiKeyVault(context).read()==expected }.getOrDefault(false)
                }
            } catch (timeout: ComposeTimeoutException) {
                // Finite UI-state evidence only: never dump semantics containing the key.
                fun enabled(tag: String): Boolean? = compose.onAllNodesWithTag(tag)
                    .fetchSemanticsNodes().singleOrNull()?.config?.contains(SemanticsProperties.Disabled)?.not()
                val feedback=compose.onAllNodesWithTag("gemini-feedback-SAVED",useUnmergedTree=true)
                    .fetchSemanticsNodes().isNotEmpty()
                throw AssertionError("KEY_PERSIST_TIMEOUT; save_enabled="+enabled("gemini-save-key")+
                    "; delete_enabled="+enabled("gemini-delete-key")+"; saved_feedback="+feedback+
                    "; awaiting_delete="+(expected==null),timeout)
            }
            compose.waitForIdle()
        }
        fun settleKeyboard() {
            // Text replacement can open the IME asynchronously. A touch click during
            // the inset/layout transition can miss the moving Save button.
            // Hide the IME through the platform, then keep exercising the real button.
            compose.runOnUiThread {
                val current=activity.activity()
                current.window.insetsController?.hide(WindowInsets.Type.ime())
            }
            compose.waitUntil(5_000) {
                var hidden=false
                compose.runOnUiThread {
                    hidden=activity.activity().window.decorView.rootWindowInsets
                        ?.isVisible(WindowInsets.Type.ime())==false
                }
                hidden
            }
            compose.waitForIdle()
        }
        fun awaitFeedback(kind: String) {
            // File publication precedes the ViewModel's IO readback and UI acknowledgement.
            // Compose idleness alone does not wait for that independent coroutine.
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("gemini-feedback-"+kind,useUnmergedTree=true)
                    .fetchSemanticsNodes().size==1
            }
            val message=compose.onNodeWithTag("gemini-feedback-"+kind,useUnmergedTree=true)
                .assertExists().fetchSemanticsNode()
            assertTrue(message.config[SemanticsProperties.Text].any { it.text.isNotBlank() })
        }
        fun saveKey(value: String) {
            compose.onNodeWithTag("gemini-key-input").performScrollTo().performTextReplacement(value)
            settleKeyboard()
            compose.onNodeWithTag("gemini-save-key").performScrollTo().assertIsEnabled().performClick()
            awaitKey(value)
            awaitFeedback("SAVED")
        }
        openSettings()
        compose.onNodeWithTag("gemini-save-key").performScrollTo().assertIsNotEnabled()
        saveKey(synthetic)
        assertEquals(synthetic,GeminiKeyVault(context).read())
        activity.restart() // Fresh Activity and ViewModel, not just a configuration recreation.
        compose.waitForIdle();openSettings()
        assertEquals(synthetic,GeminiKeyVault(context).read())
        saveKey(replacement)
        assertEquals(replacement,GeminiKeyVault(context).read())
        compose.onNodeWithTag("gemini-delete-key").performScrollTo().assertIsEnabled().performClick()
        awaitKey(null);awaitFeedback("DELETED")
        compose.onNodeWithTag("gemini-delete-key").assertIsNotEnabled()
        assertFalse(vault.hasKey())
    }
}
