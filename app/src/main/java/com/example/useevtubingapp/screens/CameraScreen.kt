package com.example.useevtubingapp.screens

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.useevtubingapp.MainActivity
import com.example.useevtubingapp.ui.theme.blueDark7
import com.example.useevtubingapp.ui.theme.blue7
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.common.internal.ImageConvertUtils
import java.io.File
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine



//функция, которая будет отвечать за захват фотографии.
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

    //cохранение
    val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    val appDir = File(picturesDir, "Usee")

    // Создаем папку если её нет
    if (!appDir.exists()) {
        appDir.mkdirs()
    }


    val photoFile = File(
        appDir,
        SimpleDateFormat(filenameFormat, Locale.US).format(System.currentTimeMillis()) + ".jpg"
    )

    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(outputOptions, executor, object: ImageCapture.OnImageSavedCallback {
        override fun onError(exception: ImageCaptureException) {
            Log.e("Usee", "Take photo error:", exception)
            onError(exception)
        }


        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
            val savedUri = Uri.fromFile(photoFile)
            onImageCaptured(savedUri)

            mainActivity.onImageCaptured(

                context=context,
                filenameFormat=filenameFormat,
                directory=outputDirectory,
                executor=executor,
                photo=photoFile,
            )
        }
    })
}


//метод для Context для создания асинхронного процесса
private suspend fun Context.getCameraProvider(): ProcessCameraProvider = suspendCoroutine { continuation ->
    ProcessCameraProvider.getInstance(this).also { cameraProvider ->
        cameraProvider.addListener({
            continuation.resume(cameraProvider.get())
        }, ContextCompat.getMainExecutor(this))
    }
}

// Вспомогательная функция для конвертации ImageProxy в Bitmap
private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    val mediaImage: Image? = imageProxy.image
    mediaImage?.let {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val bitmap : Bitmap = ImageConvertUtils.getInstance().getUpRightBitmap(image)
        return bitmap
    }
    return null
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(mainActivity: MainActivity) {
    val lensFacing = remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val preview = Preview.Builder().build()
    val previewView = remember { mainActivity.changeOutputView(PreviewView(context), context) }
    val imageCapture: ImageCapture = remember { ImageCapture.Builder().build() }
    val cameraSelector = CameraSelector.Builder()
        .requireLensFacing(lensFacing.value)
        .build()
    val outputDirectory = mainActivity.findOutputDirectory()
    val cameraExecutor = mainActivity.cameraExecutor

    // ImageAnalysis для обработки кадров
    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }


    LaunchedEffect(Unit) {
        imageAnalysis.setAnalyzer(
            ContextCompat.getMainExecutor(context),
            { imageProxy ->
                // Конвертируем в Bitmap для анализа (без сохранения)
                val bitmap = imageProxyToBitmap(imageProxy)
                Log.d("Download", "$bitmap")
                bitmap?.let {
                    Log.d("Download", "Success")
                    mainActivity.processFrameForPoseDetection(it, context)
                }
                imageProxy.close()
            }
        )
    }

    // Настраиваем анализатор для обработки кадров
    LaunchedEffect(lensFacing.value) {
        // инициализация камеры
        try {
            val cameraProvider = context.getCameraProvider()
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                imageAnalysis
            )

            preview.setSurfaceProvider(previewView.surfaceProvider)
        } catch (e : Exception) {
            Log.e("Usee", "Camera initialization error", e)
        }
    }

    // Дополнительный LaunchedEffect для принудительной инициализации при первом запуске
    LaunchedEffect(Unit) {
        // Этот блок гарантирует, что камера запустится сразу
        try {
            val cameraProvider = context.getCameraProvider()
            if (cameraProvider.isBound(imageAnalysis).not()) {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
                )
                preview.setSurfaceProvider(previewView.surfaceProvider)
            }
        } catch (e: Exception) {
            Log.e("Usee", "Initial camera init error", e)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ){
        //для последующего заполнения
        Row(
            modifier = Modifier.height(600.dp).fillMaxWidth(0.9f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Top
        ){
            if (mainActivity.shouldShowCamera.value) {
                //CameraView
                Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxSize()) {
                    AndroidView({ previewView }, modifier = Modifier.fillMaxSize())
                    Text(
                        text = mainActivity.poseResults.value,
                        color = blueDark7,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(16.dp)
                            .background(Color(0x00FFFFFF))
                    )
                }

            }
        }

        Row(
            modifier = Modifier.height(200.dp).fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ){
            //кнопка для фото
            OutlinedButton(
                onClick = { takePhoto(
                    mainActivity = mainActivity,
                    context=context,
                    filenameFormat = "yyyy-MM-dd-HH-mm-ss-SSS",
                    imageCapture = imageCapture,
                    outputDirectory = outputDirectory,
                    executor = cameraExecutor,
                    onImageCaptured = { uri -> mainActivity.handleImageCapture(uri) },
                    onError = { Log.e("Usee", "Сapture error:", it) }
                ) },
                modifier = Modifier.size(80.dp),
                contentPadding = PaddingValues(0.dp),
                border = BorderStroke(0.dp, color = Color(0x00FFFFFF)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = blueDark7),
            ) {
                Icon(
                    Icons.Filled.PhotoCamera ,
                    contentDescription = "make photo button",
                    tint=blueDark7,
                    modifier = Modifier.size(height=80.dp, width=80.dp),
                )

            }
            // кнопка для видео
            OutlinedButton(
                onClick = { },
                modifier= Modifier.size(200.dp),
                contentPadding = PaddingValues(0.dp),
                border = BorderStroke(0.dp, color=Color(0x00FFFFFF)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = blueDark7)
            ) {
                Icon(
                    Icons.Filled.MotionPhotosOn ,
                    contentDescription = "start video button",
                    tint=blueDark7,
                    modifier = Modifier.size(height=200.dp, width=200.dp)
                )
            }
            //switch для поворота камеры
            Switch(
                checked = lensFacing.value == CameraSelector.LENS_FACING_FRONT,
                onCheckedChange = { isFront ->
                    if (isFront) {
                        lensFacing.value = CameraSelector.LENS_FACING_FRONT
                    } else {
                        lensFacing.value = CameraSelector.LENS_FACING_BACK

                    } },
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
