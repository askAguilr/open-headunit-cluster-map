package com.andrerinas.openheadunit.aap

import android.content.Intent
import android.os.SystemClock
import android.view.TextureView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class ClusterMapPipTest {
    @Test fun secondaryVideoKeepsRenderingInPipAndAfterSurfaceRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, ClusterMapPreviewActivity::class.java)
        ActivityScenario.launch<ClusterMapPreviewActivity>(intent).use { scenario ->
            await { scenario.onActivity { assertTrue(it.map.presentedFrames >= 5) } }
            scenario.onActivity { activity ->
                val bitmap = (activity.map.getChildAt(0) as TextureView).bitmap!!
                val colors = (1..8).map { i -> bitmap.getPixel(bitmap.width * i / 10, bitmap.height / 2) }.toSet()
                assertTrue("Synthetic clip must produce a nonblank image", colors.size > 1)
                bitmap.recycle()
                assertTrue(activity.pip())
            }
            await { scenario.onActivity { assertTrue(it.isInPictureInPictureMode) } }
            var before = 0L
            scenario.onActivity { before = it.map.presentedFrames }
            await { scenario.onActivity { assertTrue(it.map.presentedFrames > before + 5) } }
            instrumentation.uiAutomation.executeShellCommand(
                "am start --windowingMode 1 -n com.askaguilr.openheadunit.clustermap/com.andrerinas.openheadunit.aap.ClusterMapPreviewActivity"
            ).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
            }
            await { scenario.onActivity { assertFalse(it.isInPictureInPictureMode) } }
            scenario.onActivity { it.map.hideMap() }
            SystemClock.sleep(100)
            scenario.onActivity { it.map.showMap(it.session); before = it.map.presentedFrames }
            await { scenario.onActivity { assertTrue(it.map.presentedFrames > before + 5) } }
            scenario.onActivity { activity ->
                assertTrue(activity.sent.isNotEmpty())
                assertTrue(activity.sent.all { it.channel == Channel.ID_CLUSTER_VIDEO })
                val acks = activity.sent.filter { it.type == Media.MsgType.MEDIA_MESSAGE_ACK_VALUE }
                assertTrue(acks.size > 10)
                assertTrue(acks.all { it.parse(Media.Ack.newBuilder()).sessionId == 42 })
            }
        }
    }

    private fun await(assertion: () -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (true) {
            try { assertion(); return } catch (e: AssertionError) {
                if (SystemClock.elapsedRealtime() >= deadline) throw e
                SystemClock.sleep(100)
            }
        }
    }
}
