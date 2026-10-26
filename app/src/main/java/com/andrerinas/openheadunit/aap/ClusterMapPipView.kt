package com.andrerinas.openheadunit.aap

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.andrerinas.openheadunit.R

/** Display-only map layer. The full AA projection remains underneath for an immediate return. */
internal class ClusterMapPipView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    private val texture = TextureView(context).apply { surfaceTextureListener = this@ClusterMapPipView }
    private val message = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(16, 8, 16, 8)
        setBackgroundColor(Color.BLACK)
    }
    private var session: ClusterMapSession? = null
    private var attachedSession: ClusterMapSession? = null
    private var output: Surface? = null
    private var textureOwner: ClusterMapSession? = null
    private var pendingRetirements = 0
    internal var presentedFrames: Long = 0
        private set

    init {
        setBackgroundColor(Color.BLACK)
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visibility = View.GONE
    }

    fun showMap(value: ClusterMapSession?) {
        visibility = View.VISIBLE
        bringToFront()
        if (session !== value) {
            retireOutput()
            session = value
        }
        if (value == null) showStatus(context.getString(R.string.cluster_map_disconnected))
        else if (output == null) {
            showStatus(context.getString(R.string.cluster_map_waiting))
            texture.surfaceTexture?.let { attach(it) }
        }
    }

    fun hideMap() {
        retireOutput()
        session = null
        visibility = View.GONE
    }

    private fun showStatus(text: String?) {
        message.text = text
        message.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun attach(surfaceTexture: SurfaceTexture) {
        val current = session ?: return
        if (output != null || pendingRetirements > 0) return
        val surface = Surface(surfaceTexture)
        attachedSession = current
        textureOwner = current
        output = surface
        if (!current.attach(surface) { text ->
            post {
                if (attachedSession === current && output === surface) showStatus(text)
            }
        }) {
            output = null
            attachedSession = null
            surface.release()
            showStatus(context.getString(R.string.cluster_map_disconnected))
        }
    }

    private fun retireOutput(afterRelease: () -> Unit = {}) {
        val surface = output
        val owner = attachedSession
        output = null
        attachedSession = null
        if (surface != null && owner != null) {
            pendingRetirements++
            owner.detach(surface) {
                afterRelease()
                post {
                    pendingRetirements--
                    if (visibility == View.VISIBLE) texture.surfaceTexture?.let { attach(it) }
                }
            }
        }
        else {
            surface?.release()
            afterRelease()
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { attach(surface) }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) { presentedFrames++ }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // Retain the texture until MediaCodec has released its Surface on the worker.
        val owner = textureOwner
        retireOutput()
        if (owner != null) owner.afterDecoderWork { surface.release() } else surface.release()
        textureOwner = null
        return false
    }
}
