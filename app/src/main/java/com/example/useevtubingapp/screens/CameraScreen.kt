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
import androidx.camera.core.ImageProxy
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
import androidx.lifecycle.Lifecycle
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

private fun takePhoto(
    mainActivity: MainActivity,
    context: Context,
    filenameFormat: String,
    imageCapture: ImageCapture,
    outputDirectory: File,
    executor: Executor,
    onImageCaptured: (Uri) -> Unit,
    onError: (ImageCaptureException) -> Unit
) {
    val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    val appDir = File(picturesDir, "Usee").also { if (!it.exists()) it.mkdirs() }
    val photoFile = File(appDir, SimpleDateFormat(filenameFormat, Locale.US).format(System.currentTimeMillis()) + ".jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(outputOptions, executor, object : ImageCapture.OnImageSavedCallback {
        override fun onError(exception: ImageCaptureException) {
            Log.e("Usee", "Take photo error:", exception)
            onError(exception)
        }
        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
            val savedUri = Uri.fromFile(photoFile)
            onImageCaptured(savedUri)
            mainActivity.onImageCaptured(
                context = context,
                filenameFormat = filenameFormat,
                directory = outputDirectory,
                executor = executor,
                photo = photoFile,
            )
        }
    })
}

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
    val outputDirectory = mainActivity.findOutputDirectory()
    val cameraExecutor = mainActivity.cameraExecutor

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    val renderer = remember { ModelRenderer() }
    //val modelReady = remember { mutableStateOf(false) }

    val avatarFilePath = mainActivity.getAvatar()

    val avatarFile =
        if (avatarFilePath != null) {
            remember { File(avatarFilePath) }
        } else {
            remember { null }
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

            val (width, height) = imageProxy.height.toFloat() to imageProxy.width.toFloat()

            mainActivity.processFrameForPoseDetectionFromImage(
                inputImage = inputImage,
                onCompleteSuccess = {
                    if (renderer.isReady()) {
                        val pose = mainActivity.poseResults.value
                        if (pose?.allPoseLandmarks?.isEmpty() == true){
                            renderer.hideModel()
                        } else if (pose != null){
                            mainActivity.applyPose(renderer, width, height)
                            mainActivity.applyFace(renderer)
                        } else (
                            Log.d("PoseDetection", "Don't have pose")
                        )
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
            if (avatarFilePath != null) {
                avatarFile?.delete()
            }
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
                                layoutParams = ViewGroup.LayoutParams(MATCH_PARENT,MATCH_PARENT)
                                holder.setFormat(PixelFormat.TRANSLUCENT)

                                renderer.onSurfaceAvailable(this, lifecycle, avatarFile)
                            }
                        })

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
            OutlinedButton(
                onClick = {
                    takePhoto(
                        mainActivity = mainActivity,
                        context = context,
                        filenameFormat = "yyyy-MM-dd-HH-mm-ss-SSS",
                        imageCapture = imageCapture,
                        outputDirectory = outputDirectory,
                        executor = cameraExecutor,
                        onImageCaptured = { uri -> mainActivity.handleImageCapture(uri) },
                        onError = { Log.e("CameraScreen", "Capture error:", it) }
                    )
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

            OutlinedButton(
                onClick = { },
                modifier = Modifier.size(150.dp),
                contentPadding = PaddingValues(0.dp),
                border = BorderStroke(0.dp, color = Color(0x00FFFFFF)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = blueDark7)
            ) {
                Icon(
                    Icons.Filled.MotionPhotosOn,
                    contentDescription = "start video button",
                    tint = blueDark7,
                    modifier = Modifier.size(150.dp)
                )
            }

            Switch(
                checked = lensFacing.value == CameraSelector.LENS_FACING_FRONT,
                onCheckedChange = { isFront ->
                    lensFacing.value = if (isFront) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
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