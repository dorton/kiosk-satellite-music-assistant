package net.markandrew.kiosk.music

import android.app.Activity
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.sendspindroid.playback.SendSpinEngine
import com.sendspindroid.sendspin.SendSpin
import com.sendspindroid.sendspin.SyncAudioPlayer
import kotlinx.coroutines.android.asCoroutineDispatcher
import net.markandrew.kiosk.music.receiver.PluginReceiver
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaSession
import java.util.function.Consumer

/** Invoke the actual callback used by PluginReceiver.start, not a fabricated
 * playback snapshot. Only the already-verified identity / host IO boundary is a
 * fixture: no encrypted connection, registration, MA or Android volume writes. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [30], shadows = [ReceiverPlaybackCallbackTest.RecordingSession::class])
class ReceiverPlaybackCallbackTest {
    // Robolectric's MediaController does not round-trip state through the system
    // binder. Record the actual production setPlaybackState call at that boundary.
    @Implements(MediaSession::class)
    class RecordingSession : ShadowMediaSession() {
        var recorded: PlaybackState? = null
        @Implementation protected fun setPlaybackState(state: PlaybackState?) { recorded = state }
    }
    private lateinit var receiver: PluginReceiver
    private lateinit var engine: SendSpinEngine
    private lateinit var callbacks: SendSpin.Callback
    private lateinit var session: MediaSession
    private lateinit var link: LocalVolumeLink
    private var master = 100
    private val reports = mutableListOf<Int>()
    private var held = true
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun set(target: Any, name: String, value: Any) { field(target, name).set(target, value) }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun playback() = receiver.state()["playback"]
    private fun output() = field(engine, "trackGain").getFloat(engine)
    private fun sessionState() = Shadow.extract<RecordingSession>(session).recorded!!.state
    @Before fun setup() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        receiver = PluginReceiver(context)
        set(receiver, "running", true); set(receiver, "epoch", 1L); set(receiver, "playerActive", true)
        engine = SendSpinEngine(object : SendSpinEngine.Listener {
            override fun onStreamStarted() {}
            override fun onPlayerChanged(player: SyncAudioPlayer?) {}
            override fun onPlayerStateChanged(state: com.sendspindroid.sendspin.PlaybackState) {}
        }, lowMemoryMode = { true }, playerMuted = { false }, mainDispatcher = Handler(Looper.getMainLooper()).asCoroutineDispatcher())
        set(receiver, "engine", engine)
        session = MediaSession(context, "receiver-callback-regression")
        set(receiver, "session", session)
        val factory = receiver.javaClass.getDeclaredMethod("createCallbacks", SendSpinEngine::class.java, java.lang.Long.TYPE).apply { isAccessible = true }
        callbacks = factory.invoke(receiver, engine, 1L) as SendSpin.Callback
        link = LocalVolumeLink(object : LocalVolumeLink.Port {
            override fun read(done: Consumer<Int>, fail: Consumer<String>) { done.accept(master) }
            override fun write(level: Int, done: Runnable, fail: Consumer<String>) { assertTrue(held); master = level; done.run() }
            override fun gate(muted: Boolean) { held = muted; receiver.linkedVolumeGate(muted) }
            override fun report(level: Int): Boolean { reports.add(level); return true }
            override fun later(task: Runnable, millis: Long) {}
            override fun error(text: String) { fail(text) }
        })
        receiver.observeVolume {
            val state = receiver.state()
            val token = if (state["active"] == true && state["running"] == true) "fixture-verified-local" else ""
            link.observe(token, state["playback"] as String, 20, 1L)
        }
        receiver.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }.invoke(receiver)
    }
    @After fun stop() { link.close(); receiver.stop(); idle() }
    @Test fun groupPlayingAloneUpdatesNativeSessionAndReleasesGateAfterMasterReadback() {
        assertEquals(0f, output(), 0f)
        callbacks.onGroupUpdate("queue-local", "Independent player", "playing"); idle()
        assertEquals("playing", playback()); assertEquals(PlaybackState.STATE_PLAYING, sessionState())
        assertEquals(20, master); assertFalse(held); assertEquals(1f, output(), 0f)
    }
    @Test fun groupPauseRetainsMusicMasterAndStopRestoresBaseline() {
        callbacks.onGroupUpdate("queue", "Group", "playing"); idle()
        callbacks.onGroupUpdate("queue", "Group", "paused"); idle()
        assertEquals("paused", playback()); assertEquals(20, master)
        assertEquals(PlaybackState.STATE_PAUSED, sessionState())
        callbacks.onGroupUpdate("queue", "Group", "stopped"); idle()
        assertEquals("stopped", playback()); assertEquals(100, master); assertTrue(held); assertEquals(0f, output(), 0f)
        assertFalse(reports.contains(100))
    }
    @Test fun staleMetadataCannotOverwriteAuthoritativeGroupPlaying() {
        callbacks.onGroupUpdate("queue", "Group", "playing"); idle()
        callbacks.onStateChanged("stopped"); callbacks.onStateChanged(""); idle()
        assertEquals("playing", playback()); assertEquals(20, master); assertFalse(held)
    }
    @Test fun staleMetadataCannotResumeAuthoritativeGroupPause() {
        callbacks.onGroupUpdate("queue", "Group", "paused"); callbacks.onStateChanged("playing"); idle()
        assertEquals("paused", playback()); assertEquals(100, master); assertTrue(held)
    }
    @Test fun metadataOnlyConnectionStillTracksKnownStates() {
        callbacks.onStateChanged("playing"); idle(); assertEquals("playing", playback()); assertFalse(held)
        callbacks.onStateChanged("paused"); idle(); assertEquals("paused", playback()); assertEquals(20, master)
        callbacks.onStateChanged("idle"); idle(); assertEquals("stopped", playback()); assertEquals(100, master); assertTrue(held)
    }
    @Test fun unknownOrMissingGroupFieldsDoNotInventAStopOrUnmute() {
        callbacks.onGroupUpdate("", "", "playing"); callbacks.onGroupUpdate("queue", "Group", ""); idle()
        assertEquals("stopped", playback()); assertTrue(held); assertEquals(100, master)
        callbacks.onGroupUpdate("queue", "Group", "playing"); idle()
        callbacks.onGroupUpdate("queue", "Renamed group", ""); callbacks.onStateChanged("unknown"); idle()
        assertEquals("playing", playback()); assertEquals(20, master); assertFalse(held)
    }
    @Test fun callbacksFromAnOlderConnectionCannotUnmute() {
        set(receiver, "epoch", 2L)
        callbacks.onGroupUpdate("queue", "Group", "playing"); callbacks.onStateChanged("playing"); idle()
        assertEquals("Stopped", receiver.state()["status"]); assertTrue(held); assertEquals(100, master)
    }
    @Test fun revokedPlayerRoleCannotBeRevivedByQueuedGroupUpdates() {
        callbacks.onGroupUpdate("queue", "Group", "playing"); idle()
        callbacks.onPlayerRoleChanged(false); callbacks.onGroupUpdate("queue", "Group", "playing"); idle()
        assertEquals("stopped", playback()); assertEquals(100, master); assertTrue(held)
    }
    @Test fun disconnectClearsGroupAuthorityForTheNextConnection() {
        callbacks.onGroupUpdate("queue", "Group", "playing"); idle()
        callbacks.onDisconnected(false); idle(); assertEquals("stopped", playback()); assertTrue(held)
        callbacks.onPlayerRoleChanged(true); callbacks.onStateChanged("paused"); idle()
        assertEquals("paused", playback())
    }
    @Test fun speechStillPreventsUnmuteAndFullMasterIsNotReportedToMa() {
        link.speech(true); callbacks.onGroupUpdate("queue", "Group", "playing"); idle()
        assertEquals("playing", playback()); assertEquals(100, master); assertTrue(held); assertEquals(0f, output(), 0f)
        link.speech(false); idle(); assertEquals(20, master); assertFalse(held); assertEquals(1f, output(), 0f)
        assertFalse(reports.contains(100))
    }
}
