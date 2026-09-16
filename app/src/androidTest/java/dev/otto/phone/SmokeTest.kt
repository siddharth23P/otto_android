package dev.otto.phone

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import dev.otto.phone.access.OttoAccessibilityService
import dev.otto.phone.state.Resumption
import dev.otto.phone.transport.EmbeddedTransport
import dev.otto.phone.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A whole turn on a device with no vendor key (#14): the embedded runtime with otto_app/fake.py's
 * scripted model, the real session and events, and the real phone tools through the accessibility
 * service (which .github/workflows/emulator.yml switches on before running this).
 *
 * The process-death pair runs only when the workflow asks for it (`-e otto.death start|check`): it
 * starts a slow turn, the workflow force-stops the app, and the next run checks the notice.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class SmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun fakeModelAndPermissions() {
        File(context.filesDir, EmbeddedTransport.FAKE_MODEL_FLAG).writeText("1")
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @After fun close() {
        scenario?.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // A fresh install shows the disclosure first.
        runCatching {
            compose.waitUntilAtLeastOneExists(hasTestTag("disclosure_accept"), 5_000)
            compose.onNodeWithTag("disclosure_accept").performClick()
        }
        // Connected and ready: the send button is enabled once there is text.
        compose.waitUntilAtLeastOneExists(hasTestTag("chat_input"), 60_000)
    }

    private fun send(text: String) {
        compose.onNodeWithTag("chat_input").performTextInput(text)
        compose.waitUntil(60_000) { compose.onAllNodes(hasTestTag("chat_send") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_send").performClick()
    }

    private fun bringOttoBack() {
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun death(): String? = InstrumentationRegistry.getArguments().getString("otto.death")

    @Test fun aScriptedTurnReadsThePhoneAsksAndAnswers() {
        assumeTrue(death() == null)
        val started = System.nanoTime()
        launch()
        assertNotNull("the accessibility service is not on", OttoAccessibilityService.instance)
        send("show me the display settings")
        // The script opened Settings > Display, so Otto is behind it; its question waits in the chat.
        Thread.sleep(3_000)
        bringOttoBack()
        compose.waitUntilAtLeastOneExists(hasTestTag("ask_choice_1"), 60_000)
        compose.onNodeWithTag("ask_choice_1").performClick()
        compose.waitUntilAtLeastOneExists(hasText("fake answer: you picked second", substring = true), 60_000)
        compose.waitUntilAtLeastOneExists(hasText("screen:", substring = true), 5_000)

        // Budgets: importing otto's pipeline, cold, and the whole turn.
        val imported = Python.getInstance().getModule("otto_app.fake").callAttr("pipeline_import_seconds").toDouble()
        val turnSeconds = (System.nanoTime() - started) / 1e9
        // The app's memory after a turn, this test's own included (it runs in the app's process).
        val memory = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
        val pssMb = memory.totalPss / 1024
        android.util.Log.i("OttoSmoke", "budget pipeline_import_s=$imported turn_s=$turnSeconds pss_mb=$pssMb")
        assertTrue("importing the pipeline took $imported s (budget $IMPORT_BUDGET_S)", imported in 0.0..IMPORT_BUDGET_S)
        assertTrue("the app holds $pssMb MB after a turn (budget $PSS_BUDGET_MB)", pssMb <= PSS_BUDGET_MB)
    }

    @Test fun aSlowTurnStartsAndIsLeftRunning() {
        assumeTrue(death() == "start")
        launch()
        send("a slow request")
        compose.waitUntilAtLeastOneExists(hasTestTag("status_stop"), 30_000)
        // The workflow force-stops the app from here.
    }

    @Test fun theNextStartSaysTheTurnWasInterrupted() {
        assumeTrue(death() == "check")
        launch()
        compose.waitUntilAtLeastOneExists(hasText(Resumption.INTERRUPTED), 60_000)
    }

    companion object {
        const val IMPORT_BUDGET_S = 8.0
        const val PSS_BUDGET_MB = 350
    }
}
