package com.bnyro.recorder.util

import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 把 MediaProjection 的全屏镜像裁剪成指定区域后重新编码。
 *
 * VirtualDisplay 本身只能缩放、不能裁剪，所以这里让它输出全屏画面到
 * SurfaceTexture，再由 GL 按选区采样并渲染到 MediaCodec 的输入 Surface，
 * 最后由 MediaMuxer 写成无声的 mp4。音轨由调用方单独采集后再合流。
 */
class CroppedVideoEncoder(
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val region: Rect,
    private val width: Int,
    private val height: Int,
    private val mimeType: String,
    private val bitRate: Int,
    private val frameRate: Int,
    private val outputFile: File
) {
    /** 第一帧真正进入编码的时刻（elapsedRealtimeNanos），用于与音频对齐 */
    @Volatile
    var firstFrameNanos: Long? = null
        private set

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var codecSurface: Surface? = null

    private val thread = HandlerThread("CroppedVideoEncoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val readyLatch = CountDownLatch(1)

    @Volatile
    private var setupError: Throwable? = null
    @Volatile
    private var finished = false
    private var finishDone = false

    private var surfaceTexture: SurfaceTexture? = null
    private var sourceSurface: Surface? = null

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    private var program = 0
    private var textureId = 0
    private var positionLocation = 0
    private var texCoordLocation = 0
    private var texMatrixLocation = 0
    private var positionBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null

    private val texMatrix = FloatArray(16)
    private val bufferInfo = MediaCodec.BufferInfo()

    private var muxerStarted = false
    private var outputTrack = -1
    private var firstPtsUs = -1L
    private var endSignaled = false

    /**
     * 启动编码器与 GL 管线，返回可以交给 VirtualDisplay 的全屏画面入口。
     * 失败时抛异常，调用方负责 [stop]。
     */
    fun start(): Surface {
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(mimeType)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codecSurface = codec.createInputSurface()
        codec.start()
        this.codec = codec
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        handler.post { setupGl() }
        if (!readyLatch.await(5, TimeUnit.SECONDS)) {
            throw IllegalStateException("Region encoder initialization timed out")
        }
        setupError?.let { throw IllegalStateException("Region encoder initialization failed", it) }
        return sourceSurface ?: throw IllegalStateException("Region encoder has no source surface")
    }

    /** 停止编码并写出文件尾部，必须在合并音频前调用（阻塞，勿在主线程调用） */
    fun stop() {
        if (finished) return
        finished = true
        val done = CountDownLatch(1)
        handler.post {
            try {
                finishInternal()
            } finally {
                done.countDown()
            }
        }
        if (!done.await(10, TimeUnit.SECONDS)) {
            Log.e(TAG, "Region encoder stop timed out")
        }
        thread.quitSafely()
        runCatching { thread.join(2000) }
    }

    private fun setupGl() {
        try {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw IllegalStateException("Unable to initialize EGL")
            }
            eglDisplay = display

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] <= 0 || configs[0] == null
            ) {
                throw IllegalStateException("Unable to choose an EGL config")
            }

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            val context = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                contextAttribs,
                0
            )
            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                throw IllegalStateException("Unable to create EGL context")
            }
            eglContext = context

            val surface = EGL14.eglCreateWindowSurface(
                display,
                configs[0],
                codecSurface,
                intArrayOf(EGL14.EGL_NONE),
                0
            )
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                throw IllegalStateException("Unable to create EGL window surface")
            }
            eglSurface = surface
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw IllegalStateException("Unable to make the EGL context current")
            }

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
            texMatrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix")
            val textureLocation = GLES20.glGetUniformLocation(program, "uTexture")
            GLES20.glUseProgram(program)
            GLES20.glUniform1i(textureLocation, 0)
            GLES20.glUseProgram(0)

            positionBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            // 选区归一化到 (0,0)-(1,1)，GL 纹理坐标以左下角为原点
            val left = region.left.toFloat() / screenWidth
            val right = region.right.toFloat() / screenWidth
            val top = 1f - region.top.toFloat() / screenHeight
            val bottom = 1f - region.bottom.toFloat() / screenHeight
            texCoordBuffer = floatBuffer(
                floatArrayOf(
                    left, bottom,
                    right, bottom,
                    left, top,
                    right, top
                )
            )

            textureId = createExternalTexture()
            val texture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(screenWidth, screenHeight)
                setOnFrameAvailableListener { drawFrame() }
            }
            surfaceTexture = texture
            sourceSurface = Surface(texture)
        } catch (e: Throwable) {
            Log.e(TAG, "Unable to initialize the region encoder", e)
            setupError = e
        } finally {
            readyLatch.countDown()
        }
    }

    private fun drawFrame() {
        if (finished) return
        val texture = surfaceTexture ?: return
        val display = eglDisplay ?: return
        val surface = eglSurface ?: return
        try {
            texture.updateTexImage()
            texture.getTransformMatrix(texMatrix)
            val timestampNs = texture.timestamp

            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniformMatrix4fv(texMatrixLocation, 1, false, texMatrix, 0)

            positionBuffer?.let {
                it.position(0)
                GLES20.glEnableVertexAttribArray(positionLocation)
                GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, it)
            }
            texCoordBuffer?.let {
                it.position(0)
                GLES20.glEnableVertexAttribArray(texCoordLocation)
                GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 0, it)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionLocation)
            GLES20.glDisableVertexAttribArray(texCoordLocation)

            EGLExt.eglPresentationTimeANDROID(display, surface, timestampNs)
            if (!EGL14.eglSwapBuffers(display, surface)) {
                Log.e(TAG, "eglSwapBuffers failed: ${EGL14.eglGetError()}")
            }
            if (firstFrameNanos == null) {
                firstFrameNanos = SystemClock.elapsedRealtimeNanos()
            }
            drainEncoder(false)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to render a cropped frame", e)
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = codec ?: return
        val muxer = muxer ?: return
        if (endOfStream && !endSignaled) {
            runCatching { codec.signalEndOfInputStream() }
            endSignaled = true
        }

        var retries = 0
        while (true) {
            val status = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000L else 0L)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
                if (++retries > 500) {
                    Log.e(TAG, "Timed out waiting for the encoder to drain")
                    break
                }
                continue
            }
            if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    outputTrack = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                continue
            }
            if (status < 0) continue

            val buffer = codec.getOutputBuffer(status)
            if (buffer != null && bufferInfo.size > 0 && muxerStarted &&
                bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
            ) {
                if (firstPtsUs < 0) firstPtsUs = bufferInfo.presentationTimeUs
                buffer.position(bufferInfo.offset)
                buffer.limit(bufferInfo.offset + bufferInfo.size)
                bufferInfo.presentationTimeUs =
                    (bufferInfo.presentationTimeUs - firstPtsUs).coerceAtLeast(0L)
                muxer.writeSampleData(outputTrack, buffer, bufferInfo)
            }
            val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            codec.releaseOutputBuffer(status, false)
            if (eos) break
        }
    }

    private fun finishInternal() {
        if (finishDone) return
        finishDone = true
        val encoderCodec = codec
        if (encoderCodec != null) {
            runCatching { drainEncoder(true) }
        }
        surfaceTexture?.setOnFrameAvailableListener(null)
        runCatching { sourceSurface?.release() }
        sourceSurface = null
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null

        eglDisplay?.let { display ->
            eglContext?.let { context ->
                eglSurface?.let { surface ->
                    runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
                    runCatching { EGL14.eglDestroySurface(display, surface) }
                }
                runCatching { EGL14.eglDestroyContext(display, context) }
            }
            runCatching { EGL14.eglTerminate(display) }
        }
        eglSurface = null
        eglContext = null
        eglDisplay = null

        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { codecSurface?.release() }
        codecSurface = null

        if (muxerStarted) {
            runCatching { muxer?.stop() }.onFailure {
                Log.e(TAG, "Unable to finalize the region video", it)
            }
        }
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        return textures[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("Unable to link shader program: $log")
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Unable to compile shader: $log")
        }
        return shader
    }

    companion object {
        private const val TAG = "CroppedVideoEncoder"

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
