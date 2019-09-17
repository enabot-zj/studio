package com.seanghay.studioexample

import android.content.Context
import android.graphics.*
import android.opengl.GLES20
import android.opengl.GLES20.*
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.TextureView
import com.seanghay.studio.core.StudioDrawable
import com.seanghay.studio.core.StudioRenderThread
import com.seanghay.studio.gles.annotation.GlContext
import com.seanghay.studio.gles.egl.glScope
import com.seanghay.studio.gles.graphics.Matrix4f
import com.seanghay.studio.gles.graphics.mat4
import com.seanghay.studio.gles.graphics.texture.Texture2d
import com.seanghay.studio.gles.kenburns.Kenburns
import com.seanghay.studio.gles.kenburns.SimpleKenburns
import com.seanghay.studio.gles.shader.TextureShader
import com.seanghay.studio.gles.transition.TransitionStore
import com.seanghay.studio.gles.transition.TransitionalTextureShader
import com.seanghay.studio.utils.BitmapProcessor
import com.seanghay.studio.utils.clamp
import com.seanghay.studio.utils.smoothStep
import java.util.*
import kotlin.math.abs

class VideoComposer(private val context: Context) : StudioDrawable {

    private var studioRenderThread: StudioRenderThread? = null
    private var width: Int = -1
    private var height: Int = -1
    private var isReleased = false

    private val transitions = TransitionStore.getAllTransitions()

    private val scenes = mutableListOf<Scene>()
    private val preDrawRunnables: Queue<Runnable> = LinkedList()
    private val postDrawRunnables: Queue<Runnable> = LinkedList()
    private val textureShaders = transitions.associate { it.name to TransitionalTextureShader(it) }
    private val blankTexture = Texture2d()
    private var durations = longArrayOf()

    var watermarkBitmap = watermarkBitmap()

    private val watermarkShader = TextureShader()
    private val watermarkTexture = Texture2d()

    private var mvpMatrix = mat4()
    private val kenburnsMatrix = mat4()

    var progress: Float = 0f
    var totalDuration = 0L


    fun getTransitions() = transitions

    fun getScenes(): List<Scene> = scenes

    fun insertScenes(vararg bitmaps: Bitmap) {
        for (bitmap in bitmaps) {
            val bitmapProcessor = BitmapProcessor(bitmap)
            bitmapProcessor.crop(720, 405)
            bitmapProcessor.cropType(BitmapProcessor.CropType.FIT_CENTER)
            val scene = Scene(bitmapProcessor.proceed())
            scenes.add(scene)
            preDraw { scene.setup() }
            totalDuration += scene.duration
        }

        var last = 0L
        durations = scenes.map {
            last += it.duration
            last
        }.toLongArray()
    }

    override fun onSetup() {
        textureShaders.forEach {
            it.value.setup()
        }

        blankTexture.initialize()
        blankTexture.configure(GL_TEXTURE_2D)

        watermarkShader.setup()
        watermarkTexture.initialize()
        watermarkTexture.use(GL_TEXTURE_2D) {
            watermarkTexture.configure(GL_TEXTURE_2D)
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, watermarkBitmap, 0)
        }


        preDraw {
            if (width != -1 && height != -1) {
                setMatrices()
                glScope("SetViewport") {
                    glViewport(0, 0, width, height)
                }
            }
        }

    }

    private fun setMatrices() {
        val ratio = (width.toFloat() / height.toFloat())
        val projectionMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        val outputMatrix = FloatArray(16)
        val scaleMatrix = FloatArray(16)

        Matrix.setIdentityM(scaleMatrix, 0)
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 3f, 7f)
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, -3f, 0f, 0f, 0f, 0f, -1.0f, 0.0f)
        Matrix.multiplyMM(outputMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // Scale it here, so we don't have to change our vertices
        Matrix.scaleM(scaleMatrix, 0, ratio, 1f, 1f)
        Matrix.multiplyMM(mvpMatrix.elements, 0, outputMatrix, 0, scaleMatrix, 0)
    }

    @GlContext
    private inline fun preDraw(crossinline run: () -> Unit) {
        preDrawRunnables.add(Runnable { run() })
    }

    @GlContext
    private inline fun postDraw(crossinline run: () -> Unit) {
        postDrawRunnables.add(Runnable { run() })
    }

    val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(
            surfaceTexture: SurfaceTexture?,
            width: Int,
            height: Int
        ) {
            this@VideoComposer.width = width
            this@VideoComposer.height = height

            studioRenderThread?.let {
                it.height = height
                it.width = width
            }
        }

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture?) {

        }

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture?): Boolean {
            release()
            return false
        }

        override fun onSurfaceTextureAvailable(
            surfaceTexture: SurfaceTexture?,
            width: Int,
            height: Int
        ) {
            if (surfaceTexture == null) return

            this@VideoComposer.width = width
            this@VideoComposer.height = height

            studioRenderThread = StudioRenderThread(surfaceTexture).also {
                it.height = height
                it.width = width
                it.drawable = this@VideoComposer
                it.start()
            }
        }
    }

    override fun onDraw(): Boolean {
        run(preDrawRunnables)

        glScope("EnableBlending") {
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        }

        glScope("ClearColor") {
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            glClearColor(0f, 0f, 0f, 1f)
        }

        val seekAt = totalDuration * progress
        val seekIndex = calculateIndexFromDuration(seekAt) ?: return false
        val offset = calculateSeekOffset(seekIndex, seekAt)
        val currentScene = scenes[seekIndex]

        val currentTexture = currentScene.texture
        val nextTexture = scenes.getOrNull(seekIndex + 1)?.texture ?: blankTexture

        val textureShader = textureShaders[currentScene.transition.name] ?: return false
        val interpolatedOffset = interpolateOffset(currentScene, offset).smoothStep(0f, 1f)
        textureShader.mvpMatrix = calculateMvpMatrix(offset, seekIndex)

        textureShader.progress = interpolatedOffset
        textureShader.draw(currentTexture, nextTexture)

        watermarkShader.mvpMatrix = mvpMatrix
        watermarkShader.draw(watermarkTexture)

        try {
            run(postDrawRunnables)
            Thread.sleep(10)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return true
    }

    private val newMatrix = mat4()
    private val kenburns: Kenburns = SimpleKenburns(0f, .25f)

    private fun calculateMvpMatrix(offset: Float, seekIndex: Int): Matrix4f {
        val f = seekIndex % 2

        val scale = kenburns.getValue(abs(f - offset))

        Matrix.setIdentityM(kenburnsMatrix.elements, 0)
        Matrix.setIdentityM(newMatrix.elements, 0)

        Matrix.scaleM(kenburnsMatrix.elements, 0, 1f + scale, 1f + scale, 1f)
        Matrix.multiplyMM(newMatrix.elements, 0, mvpMatrix.elements, 0, kenburnsMatrix.elements, 0)

        return newMatrix
    }

    @Synchronized
    fun release() {
        if (isReleased) return
        postDraw {
            scenes.forEach { it.release() }
            textureShaders.forEach { it.value.release() }
            studioRenderThread?.quit()
            isReleased = true
        }
    }

    private fun interpolateOffset(scene: Scene, offset: Float): Float {
        val slideDuration = scene.duration
        val transitionDuration = scene.transition.duration
        val diff = transitionDuration.toFloat() / slideDuration.toFloat()
        return ((offset - (1f - diff)) / diff).clamp(0f, 1f)
    }

    private fun run(runnables: Queue<Runnable>) {
        while (runnables.isNotEmpty()) runnables.poll()?.run()
    }

    private fun calculateIndexFromDuration(seekAt: Float): Int? {
        for ((index, value) in durations.withIndex()) {
            if (seekAt <= value) return index
        }
        return null
    }

    private fun calculateSeekOffset(index: Int, seekAt: Float): Float {
        val last = durations.getOrElse(index - 1) { 0L }
        return (seekAt - last) / scenes[index].duration.toFloat()
    }

    private fun watermarkBitmap(): Bitmap {
        val margin = 20
        val w = 720
        val h = 405

        val icon = BitmapFactory.decodeResource(context.resources, R.drawable.togness)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val iconRatio = icon.width.toFloat() / icon.height.toFloat()
        val dstWidth = (w.toFloat() / 5f).toInt()
        val dstHeight = (dstWidth / iconRatio).toInt()

        val left = w - dstWidth
        val top = h - dstHeight
        val dstRect = Rect(left - margin, top - margin, w - margin, h - margin)
        canvas.drawBitmap(icon, null, dstRect, null)
        return bitmap
    }

}