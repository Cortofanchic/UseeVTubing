package com.example.useevtubingapp

import android.content.res.AssetManager
import android.view.Choreographer
import android.view.SurfaceView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.ModelViewer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// класс для настройки параметров модели
class ModelRenderer {
    private lateinit var surfaceView: SurfaceView
    private lateinit var lifecycle: Lifecycle

    private lateinit var choreographer: Choreographer
    private lateinit var uiHelper: UiHelper

    private lateinit var modelViewer: ModelViewer

    private val assets: AssetManager
        get() = surfaceView.context.assets

    private val frameScheduler = FrameCallback()
    private lateinit var engine: Engine
    private lateinit var assetLoader: AssetLoader
    private lateinit var resourceLoader: ResourceLoader
    private lateinit var materialProvider: MaterialProvider

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            if (::choreographer.isInitialized) {
                choreographer.postFrameCallback(frameScheduler)
            }
        }

        override fun onPause(owner: LifecycleOwner) {
            if (::choreographer.isInitialized) {
                choreographer.removeFrameCallback(frameScheduler)
            }
        }

        override fun onDestroy(owner: LifecycleOwner) {
            if (::choreographer.isInitialized) {
                choreographer.removeFrameCallback(frameScheduler)
            }
            lifecycle.removeObserver(this)

            // Очищаем ресурсы в правильном порядке
            if (::assetLoader.isInitialized) {
                assetLoader.destroy()
            }
            if (::resourceLoader.isInitialized) {
                resourceLoader.destroy()
            }
            if (::materialProvider.isInitialized) {
                materialProvider.destroy()
            }
            if (::engine.isInitialized) {
                engine.destroy()
            }
        }
    }

    fun onSurfaceAvailable(surfaceView: SurfaceView, lifecycle: Lifecycle, file: File) {
        choreographer = Choreographer.getInstance()

        this.surfaceView = surfaceView
        this.lifecycle = lifecycle

        lifecycle.addObserver(lifecycleObserver)

        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            // This is needed to make the background transparent
            isOpaque = false
        }

        // Создаем движок
        engine = Engine.create()

        // Создаем MaterialProvider
        materialProvider = UbershaderProvider(engine)

        // Создаем ResourceLoader
        resourceLoader = ResourceLoader(engine)

        // Создаем AssetLoader с правильными параметрами
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())

        // Создаем ModelViewer
        modelViewer = ModelViewer(surfaceView = surfaceView, uiHelper = uiHelper)


        // This is needed so we can move the camera in the rendering
        surfaceView.setOnTouchListener { _, event ->
            if (::modelViewer.isInitialized) {
                modelViewer.onTouchEvent(event)
            }
            true
        }

        // Прозрачный фон
        if (::modelViewer.isInitialized) {
            modelViewer.scene.skybox = null
            modelViewer.view.blendMode = View.BlendMode.TRANSLUCENT
            modelViewer.renderer.clearOptions = modelViewer.renderer.clearOptions.apply {
                clear = true
                clearColor = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f)
            }
        }

        createRenderables(file)
    }

    private fun createRenderables(file: File) {
        try {
            val bytes = file.readBytes()
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(bytes)
                rewind()
            }
            if (::modelViewer.isInitialized) {
                modelViewer.loadModelGlb(buffer)
                modelViewer.transformToUnitCube()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    inner class FrameCallback : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            try {
                if (::choreographer.isInitialized) {
                    choreographer.postFrameCallback(this)
                }
                if (::modelViewer.isInitialized) {
                    modelViewer.render(frameTimeNanos)
                }
            } catch (e: Exception) {
                // Игнорируем ошибки рендеринга
            }
        }
    }
}