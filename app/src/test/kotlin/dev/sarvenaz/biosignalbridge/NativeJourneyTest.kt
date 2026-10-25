package dev.sarvenaz.biosignalbridge

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.sarvenaz.biosignalbridge.ui.*
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Journeys through the actual Activity, application engine and bundled public recording. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi", application = BridgeApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var engine: BridgeEngine

    @Before fun openPreview() {
        compose.runOnIdle { engine = ViewModelProvider(compose.activity)[BridgeViewModel::class.java].engine }
        compose.waitUntil(30_000) { engine.state.value.totalSamples == 3000L && engine.state.value.points.isNotEmpty() }
        compose.waitForIdle()
    }

    @Test fun nativeScreensShowTheRealPublicPreviewAndLocalWorkspace() {
        compose.onNodeWithText("Preview").assertIsDisplayed()
        compose.onNodeWithText("MIT-BIH · Record 100").assertIsDisplayed()
        capture("android-monitor")
        tab("Sessions")
        capture("android-sessions")
        compose.onNodeWithText("Session library").assertIsDisplayed()
        tab("Devices")
        capture("android-devices")
        compose.onNodeWithText("Open Live mode").assertIsDisplayed()
        tab("Settings")
        capture("android-settings")
        compose.onNodeWithText("Public recording").assertIsDisplayed()
    }

    @Test fun zoomAndPauseKeepPreviewSeparateFromLiveAcquisition() {
        compose.onNodeWithText("4 s").performClick()
        compose.waitUntil(10_000) { engine.state.value.showWindowSec == 4f }
        scrollTo("Start stream")
        compose.onNodeWithText("Start stream").performClick()
        compose.waitUntil(10_000) { engine.state.value.running }
        compose.onNodeWithText("Pause").performClick()
        compose.waitUntil(10_000) { !engine.state.value.running }
        assertEquals(BridgeMode.PREVIEW, engine.state.value.mode)
        assertEquals("mV", engine.state.value.unit)
        assertNull(engine.state.value.selectedDevice)
        assertFalse(engine.state.value.recording)
    }

    @Test fun aRecordedNotePassesThroughTheDialogAndRemainsWithTheSavedSession() {
        scrollTo("Record")
        compose.onNodeWithText("Record").performClick()
        compose.waitUntil(15_000) { engine.state.value.recording }
        scrollTo("Pause")
        compose.onNodeWithText("Pause").performClick()
        compose.waitUntil(10_000) { !engine.state.value.running }
        scrollTo("Mark event")
        compose.onNodeWithText("Mark event").performClick()
        compose.onNodeWithText("Mark this moment").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("Sensor adjusted")
        compose.onNodeWithText("Add marker").performClick()
        compose.waitUntil(15_000) { engine.state.value.markers.any { it.label == "Sensor adjusted" } }
        val id = engine.state.value.activeSessionId
        assertNotNull(id)
        scrollTo("Save session")
        compose.onNodeWithText("Save session").performClick()
        compose.waitUntil(15_000) { !engine.state.value.recording && engine.state.value.sessions.any { it.id == id } }
        assertEquals("Sensor adjusted", engine.state.value.markers.single().label)
    }

    private fun tab(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    private fun scrollTo(label: String) {
        compose.onNodeWithTag("workspace-list").performScrollToNode(hasText(label))
    }

    private fun capture(name: String) {
        // A fresh render tree avoids a stale RenderNode after capturing a previous tab.
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        lateinit var image: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
        }
        val destination = File("build/native-captures/$name.png")
        requireNotNull(destination.parentFile).mkdirs()
        destination.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("The native capture is empty", destination.length() > 8_000)
    }
}

/** UI-only tests isolate controls whose behaviour depends on a supplied source or callback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeUiControlTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun markerDialogAcceptsNaturalTextAndSendsItOnce() {
        var note: String? = null
        compose.setContent { BioSignalBridgeApp(fixture().copy(recording = true), BridgeActions(onMarker = { note = it })) }
        scrollTo("Mark event")
        compose.onNodeWithText("Mark event").performClick()
        compose.onNodeWithText("Add marker").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("  Posture changed  ")
        compose.onNodeWithText("Add marker").performClick()
        compose.runOnIdle { assertEquals("Posture changed", note) }
        compose.onNodeWithText("Mark this moment").assertDoesNotExist()
    }

    @Test fun liveChannelSelectionUsesTheDeclaredChannelAndRawUnit() {
        var channel = -1
        compose.setContent {
            BioSignalBridgeApp(fixture().copy(mode = BridgeMode.LIVE, channelName = "Channel 0", unit = "ADC counts", channelOptions = listOf(0 to "Channel 0", 2 to "Channel 2")), BridgeActions(onChannel = { channel = it }))
        }
        compose.onNodeWithText("ADC counts").assertIsDisplayed()
        compose.onNodeWithText("Choose channel").performClick()
        compose.onNodeWithText("Channel 2").performClick()
        compose.runOnIdle { assertEquals(2, channel) }
        compose.onNodeWithContentDescription("Signal timeline").assertIsNotEnabled()
        scrollTo("Live stream")
        compose.onNodeWithText("Live stream").assertIsNotEnabled()
        compose.onNodeWithText("Mark event").assertIsNotEnabled()
    }

    @Test fun seekAndTimeWindowControlsHaveIndependentCallbacks() {
        var seek = -1f
        var window = -1f
        compose.setContent { BioSignalBridgeApp(fixture(), BridgeActions(onSeek = { seek = it }, onWindow = { window = it })) }
        compose.onNodeWithText("16 s").performClick()
        compose.onNodeWithContentDescription("Signal timeline").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(3f) }
        compose.runOnIdle { assertEquals(16f, window); assertEquals(3f, seek) }
    }

    @Test fun sessionDeletionRequiresAnExplicitConfirmation() {
        var deleted: String? = null
        val session = UiSession("session-one", "Morning capture", "LIVE", "14 October 2026 · 09:30", 12f, 3000)
        compose.setContent { BioSignalBridgeApp(fixture().copy(tab = BridgeTab.SESSIONS, sessions = listOf(session)), BridgeActions(onDelete = { deleted = it })) }
        compose.onNodeWithContentDescription("Actions for Morning capture").performClick()
        compose.onNodeWithText("Delete session").performClick()
        compose.runOnIdle { assertNull(deleted) }
        compose.onNodeWithText("Keep session").performClick()
        compose.runOnIdle { assertNull(deleted) }
        compose.onNodeWithContentDescription("Actions for Morning capture").performClick()
        compose.onNodeWithText("Delete session").performClick()
        compose.onNodeWithText("Delete", substring = false).performClick()
        compose.runOnIdle { assertEquals("session-one", deleted) }
    }

    @Test fun sourceControlsRequestLiveWithoutClaimingAConnection() {
        var requested: BridgeMode? = null
        compose.setContent { BioSignalBridgeApp(fixture().copy(tab = BridgeTab.DEVICES), BridgeActions(onMode = { requested = it })) }
        compose.onNodeWithText("Open Live mode").performClick()
        compose.runOnIdle { assertEquals(BridgeMode.LIVE, requested) }
        compose.onNodeWithText("No devices listed").assertIsDisplayed()
    }

    private fun scrollTo(label: String) { compose.onNodeWithTag("workspace-list").performScrollToNode(hasText(label)) }
    private fun fixture() = BridgeUiState(
        points = listOf(UiPoint(0f, -.15f), UiPoint(1f, -.08f), UiPoint(2f, .7f), UiPoint(2.1f, -.2f), UiPoint(3f, -.14f)),
        durationSec = 12f, totalSamples = 3000,
    )
}
