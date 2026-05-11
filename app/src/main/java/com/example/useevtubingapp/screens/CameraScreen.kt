package com.example.useevtubingapp.screens

import android.content.Context
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.useevtubingapp.MainActivity
import com.example.useevtubingapp.ModelRenderer
import com.example.useevtubingapp.ui.theme.blue7
import com.example.useevtubingapp.ui.theme.blueDark7
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.Toast
import java.io.FileOutputStream
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

// Константы
private const val MIME_TYPE = "video/avc"
private const val BIT_RATE = 2_000_000
private const val FRAME_RATE = 30
private const val I_FRAME_INTERVAL = 1
private const val TIMEOUT_US = 10000L

// Данные вершин: x, y, z, u, v
private val VERTEX_DATA = floatArrayOf(
    -1f, -1f, 0f, 0f, 1f,
    -1f,  1f, 0f, 0f, 0f,
    1f,  1f, 0f, 1f, 0f,
    1f, -1f, 0f, 1f, 1f
)

// Индексы для отрисовки двух треугольников
private val INDEX_DATA = shortArrayOf(0, 1, 2, 0, 2, 3)

// Вершинный шейдер
private val VERTEX_SHADER_CODE = """
    attribute vec4 aPosition;
    attribute vec2 aTextureCoord;
    varying vec2 vTextureCoord;
    void main() {
        vTextureCoord = aTextureCoord;
        gl_Position = aPosition;
    }
"""

// Фрагментный шейдер
private val FRAGMENT_SHADER_CODE = """
    precision mediump float;
    varying vec2 vTextureCoord;
    uniform sampler2D uTexture;
    void main() {
        gl_FragColor = texture2D(uTexture, vTextureCoord);
    }
"""

data class EncoderResources(
    var encoder: MediaCodec? = null,
    var muxer: MediaMuxer? = null,
    var eglDisplay: EGLDisplay? = null,
    var eglSurface: EGLSurface? = null,
    var eglContext: EGLContext? = null,
    var program: Int = 0,
    var textureHandle: Int = 0,
    var vertexBuffer: FloatBuffer? = null,
    var indexBuffer: ShortBuffer? = null,
    var muxerStarted: Boolean = false,
    var trackIndex: Int = -1
)

private fun getSupportedVideoSize(requestedWidth: Int, requestedHeight: Int): Pair<Int, Int> {
    var encoder: MediaCodec? = null
    try {
        encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        val caps = encoder.codecInfo.getCapabilitiesForType(MIME_TYPE)
        val videoCaps = caps.videoCapabilities

        Log.d("VideoEncoder", "Запрошен размер: ${requestedWidth}x${requestedHeight}")

        val candidates = listOf(
            1920 to 1080, 1280 to 720, 854 to 480, 640 to 480,
            480 to 640, 720 to 1280, 1080 to 1920
        )

        // Проверяем поддерживается ли запрошенный размер
        val adjustedWidth = if (requestedWidth % 2 == 0) requestedWidth else requestedWidth + 1
        val adjustedHeight = if (requestedHeight % 2 == 0) requestedHeight else requestedHeight + 1

        if (videoCaps.isSizeSupported(adjustedWidth, adjustedHeight)) {
            Log.d("VideoEncoder", "Запрошенный размер поддерживается: ${adjustedWidth}x${adjustedHeight}")
            return Pair(adjustedWidth, adjustedHeight)
        }

        // Ищем подходящий из списка кандидатов
        for ((w, h) in candidates) {
            if (videoCaps.isSizeSupported(w, h)) {
                Log.d("VideoEncoder", "Выбран поддерживаемый размер: ${w}x${h}")
                return Pair(w, h)
            }
        }

        val closest = findClosestSupportedSize(videoCaps, requestedWidth, requestedHeight)
        Log.w("VideoEncoder", "Ближайший поддерживаемый размер: ${closest.first}x${closest.second}")
        return closest
    } catch (e: Exception) {
        Log.e("VideoEncoder", "Ошибка при определении размера видео: ${e.message}", e)
        return Pair(640, 480)
    } finally {
        encoder?.release()
    }
}

private fun findClosestSupportedSize(
    videoCaps: android.media.MediaCodecInfo.VideoCapabilities,
    targetWidth: Int,
    targetHeight: Int
): Pair<Int, Int> {
    val widths = videoCaps.supportedWidths
    val heights = videoCaps.supportedHeights

    if (widths == null || heights == null) {
        return Pair(640, 480)
    }

    var bestWidth = 640
    var bestHeight = 480
    var bestDiff = Int.MAX_VALUE

    // Проверяем стандартные разрешения
    val standardSizes = listOf(
        1920 to 1080, 1280 to 720, 854 to 480, 640 to 480,
        480 to 640, 720 to 1280, 1080 to 1920
    )

    for ((w, h) in standardSizes) {
        if (videoCaps.isSizeSupported(w, h)) {
            val diff = Math.abs(w - targetWidth) + Math.abs(h - targetHeight)
            if (diff < bestDiff) {
                bestDiff = diff
                bestWidth = w
                bestHeight = h
            }
        }
    }

    // Убеждаемся что размеры чётные
    bestWidth = if (bestWidth % 2 == 0) bestWidth else bestWidth + 1
    bestHeight = if (bestHeight % 2 == 0) bestHeight else bestHeight + 1

    return Pair(bestWidth, bestHeight)
}

fun encodeImagesToVideo(
    imagePaths: ArrayList<String>,
    outputPath: String,
    targetWidth: Int? = null,
    targetHeight: Int? = null,
    onProgress: ((Int, Int) -> Unit)? = null,
    onComplete: ((Boolean, String?) -> Unit)? = null
) {
    if (imagePaths.isEmpty()) {
        onComplete?.invoke(false, "Нет изображений")
        return
    }

    Thread {
        val resources = EncoderResources()

        try {
            // Определяем размеры видео по первому изображению
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imagePaths[0], opts)

            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                onComplete?.invoke(false, "Не удалось определить размеры изображения")
                return@Thread
            }

            var finalWidth = targetWidth ?: opts.outWidth
            var finalHeight = targetHeight ?: opts.outHeight

            // Проверяем и корректируем размеры для кодера
            val adjustedSize = getSupportedVideoSize(finalWidth, finalHeight)
            finalWidth = adjustedSize.first
            finalHeight = adjustedSize.second

            Log.d("VideoEncoder", "Финальный размер видео: ${finalWidth}x${finalHeight}, кадров: ${imagePaths.size}")

            // Настройка MediaCodec
            val format = MediaFormat.createVideoFormat(MIME_TYPE, finalWidth, finalHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            resources.encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            resources.encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // Создаём Surface через EGL
            setupEgl(resources)

            // Инициализация OpenGL
            initOpenGl(resources, finalWidth, finalHeight)

            // Запускаем кодер
            resources.encoder!!.start()

            // Готовим муксер
            val outFile = File(outputPath)
            outFile.parentFile?.mkdirs()
            resources.muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var pts = 0L

            // Цикл по всем кадрам
            for ((idx, path) in imagePaths.withIndex()) {
                var bitmap = BitmapFactory.decodeFile(path)
                if (bitmap == null) {
                    Log.e("VideoEncoder", "Не удалось загрузить: $path")
                    continue
                }

                // Масштабируем до целевого размера если нужно
                if (bitmap.width != finalWidth || bitmap.height != finalHeight) {
                    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, finalWidth, finalHeight, true)
                    bitmap.recycle()
                    bitmap = scaledBitmap
                }

                // Рендерим кадр
                renderFrame(resources, bitmap, finalWidth, finalHeight)
                bitmap.recycle()

                // Отправляем в кодировщик
                EGLExt.eglPresentationTimeANDROID(resources.eglDisplay, resources.eglSurface, pts * 1000)
                if (!EGL14.eglSwapBuffers(resources.eglDisplay, resources.eglSurface)) {
                    val error = EGL14.eglGetError()
                    Log.e("VideoEncoder", "eglSwapBuffers failed with error: $error")
                }

                // Забираем закодированные данные
                pts = drainEncoder(resources, pts, false)

                onProgress?.invoke(idx + 1, imagePaths.size)
            }

            // Сигнализируем конец потока
            drainEncoder(resources, pts, true)

            if (!resources.muxerStarted) {
                throw RuntimeException("MediaMuxer так и не запустился – нет выходных данных")
            }

            Log.d("VideoEncoder", "Видео успешно сохранено: $outputPath")
            onComplete?.invoke(true, outputPath)

        } catch (e: Exception) {
            Log.e("VideoEncoder", "Ошибка кодирования: ${e.message}", e)
            onComplete?.invoke(false, e.message)
        } finally {
            // Освобождение ресурсов
            cleanupResources(resources)
        }
    }.start()
}

private fun setupEgl(resources: EncoderResources) {
    resources.eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    if (resources.eglDisplay == EGL14.EGL_NO_DISPLAY) {
        throw RuntimeException("eglGetDisplay failed")
    }

    val version = IntArray(2)
    if (!EGL14.eglInitialize(resources.eglDisplay, version, 0, version, 1)) {
        throw RuntimeException("eglInitialize failed")
    }

    val attribList = intArrayOf(
        EGL14.EGL_RED_SIZE, 8,
        EGL14.EGL_GREEN_SIZE, 8,
        EGL14.EGL_BLUE_SIZE, 8,
        EGL14.EGL_ALPHA_SIZE, 8,
        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
        EGLExt.EGL_RECORDABLE_ANDROID, 1,
        EGL14.EGL_NONE
    )

    val configs = arrayOfNulls<EGLConfig>(1)
    val numConfigs = IntArray(1)
    if (!EGL14.eglChooseConfig(resources.eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
        throw RuntimeException("eglChooseConfig failed")
    }
    val config = configs[0]!!

    val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
    resources.eglContext = EGL14.eglCreateContext(resources.eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
    if (resources.eglContext == EGL14.EGL_NO_CONTEXT) {
        throw RuntimeException("eglCreateContext failed")
    }

    val inputSurface = resources.encoder!!.createInputSurface()
    val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
    resources.eglSurface = EGL14.eglCreateWindowSurface(resources.eglDisplay, config, inputSurface, surfaceAttribs, 0)
    if (resources.eglSurface == EGL14.EGL_NO_SURFACE) {
        throw RuntimeException("eglCreateWindowSurface failed")
    }

    if (!EGL14.eglMakeCurrent(resources.eglDisplay, resources.eglSurface, resources.eglSurface, resources.eglContext)) {
        throw RuntimeException("eglMakeCurrent failed")
    }

    Log.d("VideoEncoder", "EGL успешно инициализирован")
}

private fun initOpenGl(resources: EncoderResources, width: Int, height: Int) {
    // Создаём вершинный буфер
    resources.vertexBuffer = ByteBuffer.allocateDirect(VERTEX_DATA.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(VERTEX_DATA)
            position(0)
        }

    // Создаём индексный буфер
    resources.indexBuffer = ByteBuffer.allocateDirect(INDEX_DATA.size * 2)
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
        .apply {
            put(INDEX_DATA)
            position(0)
        }

    // Компилируем шейдеры
    val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
    val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

    // Создаём программу
    resources.program = GLES20.glCreateProgram()
    GLES20.glAttachShader(resources.program, vertexShader)
    GLES20.glAttachShader(resources.program, fragmentShader)
    GLES20.glLinkProgram(resources.program)

    // Проверяем статус линковки
    val linkStatus = IntArray(1)
    GLES20.glGetProgramiv(resources.program, GLES20.GL_LINK_STATUS, linkStatus, 0)
    if (linkStatus[0] == 0) {
        val log = GLES20.glGetProgramInfoLog(resources.program)
        throw RuntimeException("Program link error: $log")
    }

    GLES20.glUseProgram(resources.program)

    // Настраиваем атрибуты вершин
    val posHandle = GLES20.glGetAttribLocation(resources.program, "aPosition")
    GLES20.glEnableVertexAttribArray(posHandle)
    resources.vertexBuffer!!.position(0)
    GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 5 * 4, resources.vertexBuffer)

    val texHandle = GLES20.glGetAttribLocation(resources.program, "aTextureCoord")
    GLES20.glEnableVertexAttribArray(texHandle)
    resources.vertexBuffer!!.position(3)
    GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 5 * 4, resources.vertexBuffer)
    resources.vertexBuffer!!.position(0)

    // Создаём текстуру
    val textures = IntArray(1)
    GLES20.glGenTextures(1, textures, 0)
    resources.textureHandle = textures[0]
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resources.textureHandle)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

    // Настраиваем viewport
    GLES20.glViewport(0, 0, width, height)
    GLES20.glClearColor(0f, 0f, 0f, 1f)

    Log.d("VideoEncoder", "OpenGL успешно инициализирован с размером ${width}x${height}")
}

private fun loadShader(type: Int, code: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, code)
    GLES20.glCompileShader(shader)

    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    if (status[0] == 0) {
        val log = GLES20.glGetShaderInfoLog(shader)
        GLES20.glDeleteShader(shader)
        throw RuntimeException("Shader compile error: $log")
    }
    return shader
}

private fun renderFrame(
    resources: EncoderResources,
    bitmap: Bitmap,
    width: Int,
    height: Int
) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
    GLES20.glViewport(0, 0, width, height)
    GLES20.glUseProgram(resources.program)

    // Привязываем текстуру и загружаем битмап
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, resources.textureHandle)
    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

    // Рисуем
    GLES20.glDrawElements(GLES20.GL_TRIANGLES, INDEX_DATA.size, GLES20.GL_UNSIGNED_SHORT, resources.indexBuffer)

    // Проверяем ошибки OpenGL
    val error = GLES20.glGetError()
    if (error != GLES20.GL_NO_ERROR) {
        Log.e("VideoEncoder", "OpenGL error in renderFrame: $error")
    }
}

private fun drainEncoder(
    resources: EncoderResources,
    currentPts: Long,
    endOfStream: Boolean
): Long {
    var pts = currentPts
    val bufferInfo = MediaCodec.BufferInfo()

    if (endOfStream) {
        resources.encoder?.signalEndOfInputStream()
    }

    while (true) {
        val outId = resources.encoder?.dequeueOutputBuffer(bufferInfo, TIMEOUT_US) ?: return pts

        when (outId) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                if (!endOfStream) {
                    return pts
                }
                Thread.sleep(10)
                continue
            }
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                if (resources.muxerStarted) {
                    Log.w("VideoEncoder", "Формат изменился после запуска муксера")
                    continue
                }
                val outputFormat = resources.encoder?.outputFormat ?: continue
                resources.trackIndex = resources.muxer?.addTrack(outputFormat) ?: -1
                if (resources.trackIndex >= 0) {
                    resources.muxer?.start()
                    resources.muxerStarted = true
                    Log.d("VideoEncoder", "Muxer запущен, трек ${resources.trackIndex}")
                }
            }
            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                // Игнорируем, используется устаревший API
            }
            else -> {
                val encoded = resources.encoder?.getOutputBuffer(outId) ?: continue

                if (bufferInfo.size > 0 && resources.muxerStarted) {
                    bufferInfo.presentationTimeUs = pts
                    try {
                        resources.muxer?.writeSampleData(resources.trackIndex, encoded, bufferInfo)
                    } catch (e: Exception) {
                        Log.e("VideoEncoder", "Ошибка записи данных в муксер: ${e.message}")
                    }
                    pts += 1_000_000 / FRAME_RATE
                }

                resources.encoder?.releaseOutputBuffer(outId, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    return pts
                }
            }
        }
    }
}

private fun cleanupResources(resources: EncoderResources) {
    // Очистка OpenGL
    if (resources.program != 0) {
        GLES20.glDeleteProgram(resources.program)
    }
    if (resources.textureHandle != 0) {
        val textures = intArrayOf(resources.textureHandle)
        GLES20.glDeleteTextures(1, textures, 0)
    }

    // Очистка EGL
    if (resources.eglDisplay != null && resources.eglDisplay != EGL14.EGL_NO_DISPLAY) {
        EGL14.eglMakeCurrent(resources.eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (resources.eglSurface != null && resources.eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(resources.eglDisplay, resources.eglSurface)
        }
        if (resources.eglContext != null && resources.eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(resources.eglDisplay, resources.eglContext)
        }
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(resources.eglDisplay)
    }

    // Очистка кодера и муксера
    try {
        resources.encoder?.stop()
    } catch (e: Exception) {
        Log.e("VideoEncoder", "Ошибка при остановке кодера: ${e.message}")
    }
    try {
        resources.encoder?.release()
    } catch (e: Exception) {
        Log.e("VideoEncoder", "Ошибка при освобождении кодера: ${e.message}")
    }

    if (resources.muxerStarted) {
        try {
            resources.muxer?.stop()
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Ошибка при остановке муксера: ${e.message}")
        }
    }
    try {
        resources.muxer?.release()
    } catch (e: Exception) {
        Log.e("VideoEncoder", "Ошибка при освобождении муксера: ${e.message}")
    }

    Log.d("VideoEncoder", "Ресурсы очищены")
}

// Функция для уведомления галереи о видео
fun notifyGalleryAboutVideo(context: Context, filePath: String) {
    MediaScannerConnection.scanFile(
        context,
        arrayOf(filePath),
        arrayOf("video/mp4"),
        null
    )
}

// уведомление галереи о фото
fun notifyGallery(context: Context, file: File) {
    MediaScannerConnection.scanFile(
        context,
        arrayOf(file.absolutePath),
        arrayOf("image/jpeg"),
        object : MediaScannerConnection.OnScanCompletedListener {
            override fun onScanCompleted(path: String?, uri: Uri?) {
                Log.d("Gallery", "Scanned $path -> uri=$uri")
            }
        }
    )
}

private fun captureSurfaceView(surfaceView: SurfaceView, context: Context) {
    try {
        val bitmap = Bitmap.createBitmap(
            surfaceView.width,
            surfaceView.height,
            Bitmap.Config.ARGB_8888
        )

        PixelCopy.request(
            surfaceView,
            bitmap,
            { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    saveBitmapToFile(bitmap, context)
                    Toast.makeText(context, "Изображение сохранено в галерею", Toast.LENGTH_LONG)
                        .show()
                } else {
                    Log.e("Capture", "PixelCopy failed with code: $copyResult")
                    bitmap.recycle()
                    Toast.makeText(context, "Ошибка сохранения фотографии", Toast.LENGTH_LONG)
                        .show()
                }
            },
            Handler(Looper.getMainLooper())
        )

    } catch (e: Exception) {
        Log.e("CameraScreen", "Error capturing SurfaceView", e)
    }
}

private fun saveBitmapToFile(bitmap: Bitmap, context: Context): Uri? {
    return try {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val appDir = File(picturesDir, "Usee").also { if (!it.exists()) it.mkdirs() }
        val photoFile = File(appDir, "model_${System.currentTimeMillis()}.jpg")

        FileOutputStream(photoFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        }

        notifyGallery(context, photoFile)

        Uri.fromFile(photoFile)
    } catch (e: Exception) {
        Log.e("CameraScreen", "Error saving bitmap", e)
        null
    }
}

//private fun takePhoto(
//    mainActivity: MainActivity,
//    context: Context,
//    filenameFormat: String,
//    imageCapture: ImageCapture,
//    outputDirectory: File,
//    executor: Executor,
//    onImageCaptured: (Uri) -> Unit,
//    onError: (ImageCaptureException) -> Unit
//) {
//    val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
//    val appDir = File(picturesDir, "Usee").also { if (!it.exists()) it.mkdirs() }
//    val photoFile = File(
//        appDir,
//        SimpleDateFormat(filenameFormat, Locale.US).format(System.currentTimeMillis()) + ".jpg"
//    )
//    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
//
//    imageCapture.takePicture(outputOptions, executor, object : ImageCapture.OnImageSavedCallback {
//        override fun onError(exception: ImageCaptureException) {
//            Log.e("Usee", "Take photo error:", exception)
//            onError(exception)
//        }
//        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
//            val savedUri = Uri.fromFile(photoFile)
//            onImageCaptured(savedUri)
//            mainActivity.onImageCaptured(
//                context = context,
//                photo = photoFile,
//            )
//        }
//    })
//}

private suspend fun Context.getCameraProvider(): ProcessCameraProvider = suspendCoroutine { continuation ->
    ProcessCameraProvider.getInstance(this).also { future ->
        future.addListener({ continuation.resume(future.get()) }, ContextCompat.getMainExecutor(this))
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(mainActivity: MainActivity) {
    val lensFacing = remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycle

    val preview = remember { Preview.Builder().build() }
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    //val outputDirectory = mainActivity.findOutputDirectory()
    //val cameraExecutor = mainActivity.cameraExecutor

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    val renderer = remember { ModelRenderer() }

    val avatarFilePath = mainActivity.getAvatar()

    val avatarFile = if (avatarFilePath != null) {
        remember { File(avatarFilePath) }
    } else {
        remember { null }
    }

    val surfaceViewRef = remember { mutableStateOf<SurfaceView?>(null) }

    // Состояния для записи видео
    var isRecording by remember { mutableStateOf(false) }
    var framesDir by remember { mutableStateOf<File?>(null) }
    var frames by remember { mutableStateOf(ArrayList<String>()) }

    val appDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "Usee"
    ).also {
        if (!it.exists()) it.mkdirs()
    }

    // Слушатель готовности модели + начальная трансформация
    LaunchedEffect(renderer) {
        renderer.setOnModelReadyListener {
            Log.d("CameraScreen", "Model ready callback triggered")
            renderer.setInitialTransform(0.6f, 180f)
        }
    }

    // Анализатор кадров
    LaunchedEffect(renderer, renderer.isReady()) {
        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return@setAnalyzer
            }

            val inputImage = try {
                InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            } catch (e: Exception) {
                Log.e("CameraScreen", "Failed to create InputImage", e)
                imageProxy.close()
                return@setAnalyzer
            }

            val width = imageProxy.width.toFloat()
            val height = imageProxy.height.toFloat()

            mainActivity.processFrameForPoseDetectionFromImage(
                inputImage = inputImage,
                onCompleteSuccess = {
                    if (renderer.isReady()) {
                        val pose = mainActivity.poseResults.value
                        if (pose?.allPoseLandmarks?.isEmpty() == true) {
                            renderer.hideModel()
                        } else if (pose != null) {
                            mainActivity.applyPose(renderer, width, height)
                            mainActivity.applyFace(renderer)
                        } else {
                            Log.d("PoseDetection", "Don't have pose")
                        }
                    }
                },
                onComplete = { imageProxy.close() }
            )
        }
    }

    // Запуск камеры
    LaunchedEffect(lensFacing.value) {
        try {
            val cameraProvider = context.getCameraProvider()
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.Builder().requireLensFacing(lensFacing.value).build(),
                preview,
                imageCapture,
                imageAnalysis
            )
            preview.setSurfaceProvider(previewView.surfaceProvider)
            Log.d("CameraScreen", "Camera bound, lens=${lensFacing.value}")
        } catch (e: Exception) {
            Log.e("CameraScreen", "Camera init error", e)
        }
    }

    // Очистка
    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Top
        ) {
            if (mainActivity.shouldShowCamera.value) {
                if (avatarFile != null) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView({ previewView }, modifier = Modifier.fillMaxSize(0.3f))
                        AndroidView(factory = { context ->
                            SurfaceView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                                holder.setFormat(PixelFormat.TRANSLUCENT)

                                renderer.onSurfaceAvailable(this, lifecycle, avatarFile)
                                surfaceViewRef.value = this
                            }
                        }
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            modifier = Modifier.padding(10.dp),
                            text = "Модель не загружена: ${avatarFilePath ?: "null"}",
                            color = blueDark7
                        )
                        AndroidView({ previewView }, modifier = Modifier.fillMaxSize())
                    }
                }
            } else {
                Text("Камера не разрешена", modifier = Modifier.padding(16.dp))
            }
        }

        Row(
            modifier = Modifier
                .height(200.dp)
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            // Кнопка фото
            OutlinedButton(
                onClick = {
                    surfaceViewRef.value?.let { surfaceView ->
                        captureSurfaceView(surfaceView, context)
                    }
                },
                modifier = Modifier.size(80.dp),
                contentPadding = PaddingValues(0.dp),
                border = BorderStroke(0.dp, color = Color(0x00FFFFFF)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = blueDark7),
            ) {
                Icon(
                    Icons.Filled.PhotoCamera,
                    contentDescription = "make photo button",
                    tint = blueDark7,
                    modifier = Modifier.size(80.dp),
                )
            }

            // Кнопка записи видео
            OutlinedButton(
                onClick = {
                    try {
                        if (!isRecording) {
                            // Начало записи
                            isRecording = true
                            frames = ArrayList<String>()


                            // Создаем временную папку для кадров
                            val dir = File(appDir, "video_frames_${System.currentTimeMillis()}")
                            if (!dir.exists()) {
                                dir.mkdirs()
                            }
                            framesDir = dir

                            Log.d("CameraScreen", "Recording started, dir=${dir.absolutePath}")

                            // Захватываем кадры и сохраняем в файл
                            renderer.startFrameCapture { bitmap ->
                                if (isRecording) {
                                    val frameFile = File(dir, "frame_${String.format("%05d", frames.size)}.jpg")
                                    try {
                                        FileOutputStream(frameFile).use { out ->
                                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                            out.flush()
                                        }
                                        frames.add(frameFile.path)
                                    } catch (e: Exception) {
                                        Log.e("CameraScreen", "Error saving frame", e)
                                    }
                                }
                            }

                            Toast.makeText(context, "Запись начата", Toast.LENGTH_SHORT).show()

                        } else {
                            // Остановка записи
                            isRecording = false
                            renderer.stopFrameCapture()

                            val dir = framesDir

                            if (dir != null && frames.isNotEmpty()) {
                                Toast.makeText(
                                    context,
                                    "Создаю видео из ${frames.size} кадров...",
                                    Toast.LENGTH_LONG
                                ).show()

                                try {

                                    val outputPath = File(appDir, "video_${System.currentTimeMillis()}.mp4").absolutePath

                                    Log.d("VideoEncoder", "Output path: $outputPath")

                                    encodeImagesToVideo(
                                        imagePaths = ArrayList(frames),
                                        outputPath = outputPath,
                                        onProgress = { current, total ->
                                            Log.d("VideoEncoder", "Progress: $current/$total")
                                        },
                                        onComplete = { success, path ->
                                            if (success) {
                                                notifyGalleryAboutVideo(context, path ?: outputPath)
                                                Handler(Looper.getMainLooper()).post {
                                                    Toast.makeText(context, "Видео сохранено", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                Handler(Looper.getMainLooper()).post {
                                                    Toast.makeText(context, "Ошибка сохранения видео: $path", Toast.LENGTH_LONG).show()
                                                }
                                            }

                                            // Удаляем временные кадры
                                            try {
                                                for (frame in frames) {
                                                    File(frame).delete()
                                                }
                                                dir.delete()
                                            } catch (e: Exception) {
                                                Log.e("CameraScreen", "Error deleting temp files", e)
                                            }
                                        }
                                    )
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Запись не сохранена: ${e.message}", Toast.LENGTH_SHORT).show()
                                    Log.e("VideoEncoder", "Ошибка $e")

                                    // Удаляем временные кадры даже при ошибке
                                    for (frame in frames) {
                                        File(frame).delete()
                                    }
                                    dir.delete()
                                }
                            } else {
                                Toast.makeText(context, "Нет кадров для видео", Toast.LENGTH_SHORT).show()
                            }

                            framesDir = null
                            frames.clear()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Поле 3D модели не загружено", Toast.LENGTH_LONG).show()
                        isRecording = false
                    }
                },
                modifier = Modifier.size(150.dp),
                contentPadding = PaddingValues(0.dp),
                border = BorderStroke(0.dp, color = Color(0x00FFFFFF)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = blueDark7),

            ) {
                Icon(
                    Icons.Filled.Adjust,
                    contentDescription = if (isRecording) "stop video button" else "start video button",
                    tint = blueDark7,
                    modifier = if (isRecording)  Modifier.size(100.dp) else Modifier.size(150.dp)
                )
            }

            // Переключатель камеры
            Switch(
                checked = lensFacing.value == CameraSelector.LENS_FACING_FRONT,
                onCheckedChange = { isFront ->
                    lensFacing.value = if (isFront) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                    renderer.setMirrored(isFront)
                },
                modifier = Modifier.size(100.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = blueDark7,
                    checkedTrackColor = Color(0x00FFFFFF),
                    checkedBorderColor = blueDark7,
                    checkedIconColor = blueDark7,
                    uncheckedThumbColor = blue7,
                    uncheckedTrackColor = Color(0x00FFFFFF),
                    uncheckedBorderColor = blue7,
                    uncheckedIconColor = blue7,
                ),
            )
        }
    }
}