package com.example.timescapedemo

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.viewpager2.widget.ViewPager2
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InlineVideoAudioFocusTest {
    @Test
    fun preparingAndSeekingPausedVideoDoesNotInterruptOtherAudio() = video { f ->
        f.onMain { f.view.seekTo(1000) }
        f.assertOtherAudioUninterrupted()
        f.onMain {
            assertFalse(f.view.isPlaying)
            assertFalse(f.view.hasAudioFocus)
        }
    }

    @Test
    fun cancelingAQueuedStartDoesNotTakeFocus() = video { f ->
        f.onMain {
            f.ready = false
            f.view.setVideoURI(Uri.fromFile(f.file))
            f.view.start()
            assertTrue(f.view.isPlaybackRequested)
            f.view.pause()
        }
        f.await { f.ready }
        f.assertOtherAudioUninterrupted()
        f.onMain {
            assertFalse(f.view.isPlaying)
            assertFalse(f.view.isPlaybackRequested)
            assertFalse(f.view.hasAudioFocus)
        }
    }

    @Test
    fun mutedPlaybackAndSourceReloadLeaveOtherAudioPlaying() = video(muted = true) { f ->
        f.onMain { f.view.start() }
        f.await { f.view.isPlaying }
        f.assertOtherAudioUninterrupted()
        f.onMain {
            f.ready = false
            f.view.setVideoURI(Uri.fromFile(f.file))
            f.view.start()
        }
        f.await { f.ready && f.view.isPlaying }
        f.assertOtherAudioUninterrupted()
        f.onMain { assertFalse(f.view.hasAudioFocus) }
    }

    @Test
    fun audiblePlaybackTakesFocusAndPauseReleasesIt() = video { f ->
        f.onMain { f.view.start() }
        f.await { f.view.isPlaying && f.lossEvents.isNotEmpty() }
        f.onMain {
            assertTrue(f.view.hasAudioFocus)
            f.view.pause()
            assertFalse(f.view.hasAudioFocus)
            f.requestOtherAudio()
        }
        f.assertOtherAudioUninterrupted()
        f.onMain { assertFalse(f.view.isPlaying) }
    }

    @Test
    fun mutingReleasesFocusAndUnmutingRequestsItWithoutReloading() = video(muted = true) { f ->
        f.onMain { f.view.start() }
        f.await { f.view.isPlaying && f.view.currentPosition > 300 }
        var position = 0
        f.onMain {
            position = f.view.currentPosition
            f.view.setMuted(false)
        }
        f.await { f.lossEvents.isNotEmpty() }
        f.onMain {
            assertTrue(f.view.isPlaying)
            assertTrue(f.view.hasAudioFocus)
            assertTrue(f.view.currentPosition >= position)
            f.view.setMuted(true)
            assertFalse(f.view.hasAudioFocus)
            f.requestOtherAudio()
        }
        f.assertOtherAudioUninterrupted()
        f.onMain { assertTrue(f.view.isPlaying) }
    }

    @Test
    fun videoWithoutAudioTrackDoesNotRequestFocus() = video(audioTrack = false) { f ->
        f.onMain { f.view.start() }
        f.await { f.view.isPlaying }
        f.assertOtherAudioUninterrupted()
        f.onMain { assertFalse(f.view.hasAudioFocus) }
    }

    @Test
    fun focusLossPausesAndDoesNotAutomaticallyReclaimFocus() = video { f ->
        var interruptions = 0
        f.onMain {
            f.view.onPlaybackInterrupted = { interruptions++ }
            f.view.start()
        }
        f.await { f.view.isPlaying && f.view.hasAudioFocus }
        f.onMain { f.requestOtherAudio() }
        f.await { !f.view.isPlaying && !f.view.hasAudioFocus && interruptions == 1 }
        f.assertOtherAudioUninterrupted()
        f.onMain { assertFalse(f.view.isPlaybackRequested) }
    }

    @Test
    fun disablingHidingAndDetachingReleaseFocusAndRejectPlayback() = video { f ->
        for (action in 0..2) {
            f.onMain {
                f.view.visibility = View.VISIBLE
                if (f.view.parent == null) f.root.addView(f.view, FrameLayout.LayoutParams(400, 400))
                f.view.setPlaybackAllowed(true)
            }
            f.await { f.view.isAttachedToWindow && f.view.isShown && f.view.duration > 0 }
            f.onMain { f.view.start() }
            f.await { f.view.isPlaying && f.view.hasAudioFocus }
            f.onMain {
                when (action) {
                    0 -> f.view.setPlaybackAllowed(false)
                    1 -> f.view.visibility = View.GONE
                    2 -> f.root.removeView(f.view)
                }
                assertFalse(f.view.hasAudioFocus)
                f.requestOtherAudio()
                f.view.start()
            }
            f.assertOtherAudioUninterrupted()
            f.onMain {
                assertFalse(f.view.isPlaying)
                assertFalse(f.view.isPlaybackRequested)
            }
        }
    }

    @Test
    fun backgroundingVisibleVideoReleasesFocus() = video { f ->
        f.onMain { f.view.start() }
        f.await { f.view.isPlaying && f.view.hasAudioFocus }
        f.scenario.moveToState(Lifecycle.State.CREATED)
        f.onMain {
            assertFalse(f.view.hasAudioFocus)
            assertFalse(f.view.isPlaying)
            f.view.start()
            assertFalse(f.view.isPlaybackRequested)
        }
    }

    @Test
    fun preloadedCardCannotPlayUntilEnabledAndFocusLossUpdatesItsControls() = video { f ->
        lateinit var recycler: RecyclerView
        lateinit var manager: RightRailFlowLayoutManager
        lateinit var adapter: CardsAdapter
        val states = mutableListOf<Boolean>()
        f.onMain {
            f.root.removeAllViews()
            manager = RightRailFlowLayoutManager(340, 360, 138, 8)
            adapter = CardsAdapter(
                TintStyle.None, {}, { _, _ -> }, { _, _ -> false }, {},
                onVideoPlaybackStateChanged = { _, playing -> states += playing }
            )
            recycler = RecyclerView(f.root.context).apply {
                layoutManager = manager
                itemAnimator = null
                this.adapter = adapter
            }
            f.root.addView(recycler, FrameLayout.LayoutParams(-1, -1))
            val data = VideoCardData(
                Uri.fromFile(f.file).toString(), f.file.name, 8000, 0, 96, 96, 1f,
                f.file.length(), null, null, null, "audio-focus-fixture"
            )
            adapter.submitList(listOf(CardItem(100, "Disposable video", "",
                backSnippet = "Disposable reverse", video = data))) {
                manager.restoreState(0, true)
                adapter.setActiveVideoCardId(100)
            }
        }
        fun holder(): CardsAdapter.VH = requireNotNull(
            recycler.findViewHolderForAdapterPosition(0) as? CardsAdapter.VH
        )
        f.await { recycler.childCount > 0 && holder().presentation.expansionProgress == 1f }
        f.assertOtherAudioUninterrupted()
        f.onMain {
            assertEquals(View.GONE, holder().videoInlineView.visibility)
            assertTrue(holder().videoInlineView.duration <= 0)
            adapter.setVideoPlaybackEnabled(true)
            adapter.setActiveVideoCardId(100)
        }
        f.await { holder().videoInlineView.isPlaying && f.lossEvents.isNotEmpty() }
        f.onMain { f.requestOtherAudio() }
        f.await { !holder().videoInlineView.isPlaying && states.lastOrNull() == false }
        f.onMain {
            assertEquals(false, adapter.getItemAt(0)?.video?.isPlaying)
            assertEquals(f.root.context.getString(R.string.video_play),
                holder().videoPlayPauseButton.contentDescription)
            assertFalse(holder().videoInlineView.hasAudioFocus)
            adapter.setGlobalVideoMuted(true)
            holder().videoPlayPauseButton.performClick()
        }
        f.await { holder().videoInlineView.isPlaying }
        f.assertOtherAudioUninterrupted()
        f.onMain {
            assertEquals(HandwritingFace.BACK, adapter.toggleCardFace(holder()))
        }
        f.await { !holder().flip.isFlipping && holder().boundFace == HandwritingFace.BACK }
        f.onMain {
            assertFalse(holder().videoInlineView.isPlaying)
            assertEquals(View.GONE, holder().videoInlineView.visibility)
            assertEquals(true, adapter.getItemAt(0)?.video?.isPlaying)
            assertEquals(HandwritingFace.FRONT, adapter.toggleCardFace(holder()))
        }
        f.await {
            !holder().flip.isFlipping && holder().boundFace == HandwritingFace.FRONT &&
                holder().videoInlineView.isPlaying
        }
        f.assertOtherAudioUninterrupted()
        f.onMain {
            holder().videoInlineView.setVideoURI(Uri.fromFile(f.file))
            holder().videoPlayPauseButton.performClick()
            assertTrue(holder().videoInlineView.isPlaybackRequested)
            holder().videoPlayPauseButton.performClick()
            assertFalse(holder().videoInlineView.isPlaybackRequested)
            assertEquals(false, adapter.getItemAt(0)?.video?.isPlaying)
        }
        f.await { holder().videoInlineView.duration > 0 }
        f.onMain {
            assertFalse(holder().videoInlineView.isPlaying)
            holder().videoPlayPauseButton.performClick()
        }
        f.await { holder().videoInlineView.isPlaying }
        f.assertOtherAudioUninterrupted()
        f.onMain {
            adapter.setVideoPlaybackEnabled(false)
            assertFalse(holder().videoInlineView.isPlaying)
            adapter.setActiveVideoCardId(null)
        }
        f.await { holder().videoInlineView.visibility == View.GONE }
        f.assertOtherAudioUninterrupted()
        f.onMain { recycler.adapter = null }
    }

    @Test
    fun appLaunchAndOffscreenFlowLeaveOtherAudioUninterrupted() = video { f ->
        var main: MainActivity? = null
        var originalIndex: Int? = null
        f.onMain { f.root.context.startActivity(Intent(f.root.context, MainActivity::class.java)) }
        try {
            f.await {
                main = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
                val activity = main ?: return@await false
                val pager = field(activity, "flowPager") as? ViewPager2 ?: return@await false
                val controllers = field(activity, "flowControllers") as? Map<*, *> ?: return@await false
                val flows = (field(activity, "flows") as? List<*>)?.filterIsInstance<CardFlow>()
                    ?: return@await false
                val active = flows.getOrNull(pager.currentItem) ?: return@await false
                val controller = controllers[active.id] ?: return@await false
                field(controller, "cardsReadyForTransition") == true
            }
            f.assertOtherAudioUninterrupted()
            val activity = requireNotNull(main)
            lateinit var pager: ViewPager2
            var videoIndex = -1
            var neighborIndex = -1
            f.onMain {
                pager = requireNotNull(field(activity, "flowPager") as? ViewPager2)
                originalIndex = pager.currentItem
                val flows = requireNotNull(field(activity, "flows") as? List<*>)
                    .filterIsInstance<CardFlow>()
                videoIndex = flows.indexOfFirst { it.id == 0L }
                neighborIndex = if (videoIndex < flows.lastIndex) videoIndex + 1 else videoIndex - 1
                assumeTrue("Requires a neighboring non-video flow", videoIndex >= 0 && neighborIndex >= 0)
                pager.setCurrentItem(neighborIndex, false)
            }
            fun videoController(): Any? =
                (field(activity, "flowControllers") as? Map<*, *>)?.get(0L)
            fun isVisible(controller: Any): Boolean = controller.javaClass
                .getDeclaredMethod("isVideoPlaybackVisible").apply { isAccessible = true }
                .invoke(controller) == true
            f.await {
                val controller = videoController() ?: return@await false
                val adapter = field(controller, "adapter") as? CardsAdapter ?: return@await false
                !isVisible(controller) && field(adapter, "videoPlaybackEnabled") == false
            }
            f.assertOtherAudioUninterrupted()
            f.onMain { pager.setCurrentItem(videoIndex, false) }
            f.await {
                val controller = videoController() ?: return@await false
                val adapter = field(controller, "adapter") as? CardsAdapter ?: return@await false
                isVisible(controller) && field(adapter, "videoPlaybackEnabled") == true
            }
            f.onMain {
                activity.startActivity(
                    Intent(activity, TimescapeFlowTestActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
            f.await {
                val controller = videoController() ?: return@await false
                val adapter = field(controller, "adapter") as? CardsAdapter ?: return@await false
                field(activity, "isVideoPlaybackForeground") == false &&
                    !isVisible(controller) && field(adapter, "videoPlaybackEnabled") == false
            }
            f.assertOtherAudioUninterrupted()
        } finally {
            f.onMain {
                main?.let { activity ->
                    originalIndex?.let { index ->
                        (field(activity, "flowPager") as? ViewPager2)?.setCurrentItem(index, false)
                        activity.javaClass.getDeclaredMethod("saveState")
                            .apply { isAccessible = true }.invoke(activity)
                    }
                    activity.finish()
                }
            }
            f.await { f.root.isAttachedToWindow && f.root.windowVisibility == View.VISIBLE }
        }
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun video(
        muted: Boolean = false,
        audioTrack: Boolean = true,
        test: (Fixture) -> Unit
    ) {
        ActivityScenario.launch(TimescapeFlowTestActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { activity ->
                fixture = Fixture(activity, scenario, muted, audioTrack)
                activity.setContentView(fixture.root)
                fixture.requestOtherAudio()
                fixture.view.setVideoURI(Uri.fromFile(fixture.file))
            }
            try {
                fixture.await { fixture.ready && fixture.view.duration > 0 }
                test(fixture)
            } finally {
                fixture.onMain {
                    fixture.view.stopPlayback()
                    fixture.root.removeAllViews()
                    fixture.audioManager.abandonAudioFocusRequest(fixture.otherAudioRequest)
                }
                fixture.file.delete()
            }
        }
    }

    private class Fixture(
        activity: TimescapeFlowTestActivity,
        val scenario: ActivityScenario<TimescapeFlowTestActivity>,
        muted: Boolean,
        audioTrack: Boolean
    ) {
        val root = FrameLayout(activity)
        val view = InlineVideoView(activity)
        val audioManager = requireNotNull(activity.getSystemService(AudioManager::class.java))
        val lossEvents = mutableListOf<Int>()
        var ready = false
        private var error: String? = null
        val file = File.createTempFile("timescape-audio-focus-", ".mp4", activity.cacheDir).apply {
            val name = if (audioTrack) "inline-video-with-audio.mp4" else "inline-video-without-audio.mp4"
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
                outputStream().use(input::copyTo)
            }
        }
        val otherAudioRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0) lossEvents += change
            }, Handler(Looper.getMainLooper()))
            .build()

        init {
            root.addView(view, FrameLayout.LayoutParams(400, 400))
            view.setPlaybackAllowed(true)
            view.setMuted(muted)
            view.setOnPreparedListener { player ->
                player.isLooping = true
                ready = true
            }
            view.setOnErrorListener { _, what, extra ->
                error = "MediaPlayer error $what/$extra"
                true
            }
        }

        fun requestOtherAudio() {
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                audioManager.requestAudioFocus(otherAudioRequest))
            lossEvents.clear()
        }

        fun assertOtherAudioUninterrupted() {
            SystemClock.sleep(350)
            onMain { assertTrue("Unexpected background-audio focus loss: $lossEvents", lossEvents.isEmpty()) }
        }

        fun onMain(block: () -> Unit) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        }

        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 10000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                onMain {
                    check(error == null) { error.orEmpty() }
                    ready = condition()
                }
                if (ready) return
                SystemClock.sleep(40)
            }
            throw AssertionError("Video did not reach the expected playback/focus state")
        }
    }
}
