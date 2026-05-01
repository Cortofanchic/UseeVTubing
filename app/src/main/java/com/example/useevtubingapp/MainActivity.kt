package com.example.useevtubingapp

import android.os.Bundle
import kotlin.toString
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.useevtubingapp.screens.*
import com.example.useevtubingapp.ui.theme.UseeTheme
import com.example.useevtubingapp.utils.Constants
import com.google.accompanist.navigation.animation.AnimatedNavHost
import com.google.accompanist.navigation.animation.composable
import com.google.accompanist.navigation.animation.rememberAnimatedNavController
import com.example.useevtubingapp.ui.theme.blue7
import com.example.useevtubingapp.ui.theme.blueDark7
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import androidx.core.graphics.scale
import com.example.useevtubingapp.screens.GalleryScreen
import com.example.useevtubingapp.screens.WelcomScreen
import com.google.android.filament.utils.Utils
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.max
import androidx.appcompat.app.AlertDialog
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

class MainActivity : ComponentActivity() {
    companion object {
        init {
            Utils.init()
            System.loadLibrary("native-lib")
        }
    }

    var uiStateSaved: String? = null

    private var lastProcessedTime = 0L
    private val frameProcessingInterval = 500 // ms

    private var options = AccuratePoseDetectorOptions.Builder()
        .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
        .build()

    var poseDetector = PoseDetection.getClient(options)

    val optionsFace = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE) // или FAST
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL) // для определения ключевых точек
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)   // для получения контуров
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // для улыбки/глаз
        .setMinFaceSize(0.15f) // минимальный размер лица (15% от ширины кадра)
        .enableTracking() // для отслеживания лица между кадрами
        .build()

    val faceDetector = FaceDetection.getClient(optionsFace)

    var shouldShowPhoto: MutableState<Boolean> = mutableStateOf(false)
    var shouldWork: MutableState<Boolean> = mutableStateOf(false)

    lateinit var outputDirectory: File
    lateinit var cameraExecutor: ExecutorService

    var shouldShowCamera: MutableState<Boolean> = mutableStateOf(false)
    var shouldUseMicrophone: MutableState<Boolean> = mutableStateOf(false)
    var poseResults = mutableStateOf(null as Pose?)
    var faceResults = mutableStateOf(null as Face?)
    // Ланчер для нескольких разрешений
    private val requestPermissionLauncherForArray = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        shouldWork.value = allGranted
        if (!shouldWork.value) {
            showPermissionDialog()
        } else {
            Log.d("MainActivity", "Все разрешения хранилища получены")
        }
    }

    // Ланчер для Android 11+ (MANAGE_EXTERNAL_STORAGE)
    private val requestManageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            shouldWork.value = Environment.isExternalStorageManager()
            if (!shouldWork.value) {
                showPermissionDialog()
            }
        }
    }

    private val requestPermissionLauncherCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            shouldShowCamera.value = true
            shouldShowPhoto.value = true
        } else {
            shouldShowCamera.value = false
            shouldShowPhoto.value = false
        }
        requestMicrophonPermission()
    }

    private val requestPermissionLauncherMicrophon = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        shouldUseMicrophone.value = isGranted
    }

    fun loadAvatar(avatarName: String) {
        uiStateSaved = avatarName
    }

    fun getAvatar(): String? {
        return uiStateSaved
    }

    fun handleImageCapture(uri: Uri) {
        Log.i("Usee", "Image captured: $uri")
        shouldShowPhoto.value = true
    }

    fun onImageCaptured(
        context: Context,
        directory: File,
        photo: File,
        executor: Executor,
        filenameFormat: String
    ) {
        val uri = Uri.fromFile(photo)
        processUri(uri, context)
        handleImageCapture(uri)
    }

    fun findOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists()) mediaDir else filesDir
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onStop() {
        super.onStop()
        shouldShowCamera.value = false
    }

    override fun onRestart() {
        super.onRestart()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            shouldShowCamera.value = true
        }
    }

    private fun requestCameraPermission() {
        requestPermissionLauncherCamera.launch(Manifest.permission.CAMERA)
    }

    private fun requestMicrophonPermission() {
        requestPermissionLauncherMicrophon.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun checkAndRequestWritePermission() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11+
                if (Environment.isExternalStorageManager()) {
                    shouldWork.value = true
                } else {
                    shouldWork.value = false
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    requestManageStorageLauncher.launch(intent)
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+
                val hasPermission = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    shouldWork.value = true
                } else {
                    shouldWork.value = false
                    requestPermissionLauncherForArray.launch(
                        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                    )
                }
            }
            else -> {
                // Android 6-12
                val hasReadPermission = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
                val hasWritePermission = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

                if (hasReadPermission && hasWritePermission) {
                    shouldWork.value = true
                } else {
                    shouldWork.value = false
                    requestPermissionLauncherForArray.launch(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    )
                }
            }
        }
    }

    fun processUri(uri: Uri, context: Context) {
        try {
            val image = InputImage.fromFilePath(context, uri)
            poseDetector.process(image)
                .addOnSuccessListener { pose ->
                    poseResults.value = pose
                }
                .addOnFailureListener { e ->
                    poseResults.value = null
                }
            faceDetector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        faceResults.value = faces[0]
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("FaceDetection", "Ошибка", e)
                }
        } catch (e: IOException) {
            poseResults.value = null
            Toast.makeText(context, "$e", Toast.LENGTH_LONG).show()
        }
    }

    fun deleteFile(file: File): Boolean {
        if (!file.exists()) {
            return true
        }
        try {
            file.delete()
            return true
        } catch (e: Exception){
            Log.e("Usee", "Exception while deleting file $e")
            return false
        }
    }

    private fun scaleBitmapForProcessing(bitmap: Bitmap): Bitmap {
        val maxDimension = 640
        if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val scale = maxDimension.toFloat() / max(bitmap.width, bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            return bitmap.scale(newWidth, newHeight).also {
                if (it != bitmap) {
                    bitmap.recycle()
                }
            }
        } else {
            return bitmap
        }
    }

    fun processFrameForPoseDetection(bitmap: Bitmap, context: Context) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < frameProcessingInterval) {
            return
        }
        lastProcessedTime = currentTime

        try {
            val scaledBitmap = scaleBitmapForProcessing(bitmap)
            val image = InputImage.fromBitmap(scaledBitmap, 0)

            poseDetector.process(image)
                .addOnSuccessListener { results ->
                    poseResults.value = results
                }
                .addOnFailureListener {
                    poseResults.value = null
                }
            faceDetector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        faceResults.value = faces[0]
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("FaceDetection", "Ошибка", e)
                }

        } catch (e: Exception) {
            Log.e("Usee", "Error processing frame", e)
            poseResults.value = null
            faceResults.value = null
        }
    }

    fun applyPose(renderer: ModelRenderer, width: Float, height: Float){
        if (renderer.isReady()) {
            poseResults.value?.let {
                renderer.applyPoseToGLB(poseResults.value!!, width, height)
            } ?: {
                Log.d("ModelRenderer", "Pose don't found")
            }
        }
    }

    fun applyFace(renderer: ModelRenderer){
        if (renderer.isReady()) {
            faceResults.value?.let {
                renderer.applyFaceToGLB(faceResults.value!!)
            } ?: {
                Log.d("ModelRenderer", "Face don't found")
            }
        }
    }

    fun changeOutputView(previewView: PreviewView, context: Context): PreviewView {
        try {
            if (previewView.width > 0 && previewView.height > 0) {

                val bitmap = Bitmap.createBitmap(
                    previewView.width,
                    previewView.height,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                previewView.draw(canvas)

                val tempFile =
                    File(context.cacheDir, "preview_usee_${System.currentTimeMillis()}.jpg")
                FileOutputStream(tempFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                }

                bitmap.recycle()
                val uri = Uri.fromFile(tempFile)
                processUri(uri, context)
            }
        } catch (e: Exception) {
            Log.e("Usee", "Error converting PreviewView to Bitmap", e)
        }
        return previewView
    }

    // createFilePath
    fun createFilePath(inputPath: String): String {
        val appDir = File(filesDir, "models")

        if (!appDir.exists()) {
            appDir.mkdirs()
        }

        val inputFile = File(inputPath)
        val fileName = "${inputFile.nameWithoutExtension}.glb"
        val outputFile = File(appDir, fileName)

        if (!outputFile.exists()) {
            outputFile.createNewFile()
        }

        return outputFile.absolutePath
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Требуются разрешения")
            .setMessage("Для работы приложения необходим доступ к файлам. Пожалуйста, предоставьте разрешения.")
            .setPositiveButton("Дать разрешения") { _, _ ->
                checkAndRequestWritePermission()
            }
            .setNegativeButton("Выйти") { _, _ ->
                finishAffinity()
            }
            .setCancelable(false)
            .show()
    }

    fun processFrameForPoseDetectionFromImage(
        inputImage: InputImage,
        onComplete: () -> Unit,
        onCompleteSuccess: () -> Unit
    ) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < frameProcessingInterval) {
            onComplete()
            return
        }
        lastProcessedTime = currentTime

        var completed = 0
        val total = 2

        fun safeComplete() {
            completed++
            if (completed >= total) {
                onComplete()
                onCompleteSuccess()
            }
        }

        // Pose
        try {
            poseDetector.process(inputImage)
                .addOnSuccessListener { pose ->
                    poseResults.value = pose
                    safeComplete()
                    onCompleteSuccess()
                }
                .addOnFailureListener { e ->
                    Log.e("MainActivity", "Pose detection failed", e)
                    poseResults.value = null
                    safeComplete()
                    onCompleteSuccess()
                }
        } catch (e: Exception) {
            Log.e("MainActivity", "Pose exception", e)
            poseResults.value = null
            safeComplete()
        }

        // Face
        try {
            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    faceResults.value = faces.firstOrNull()
                    safeComplete()
                }
                .addOnFailureListener { e ->
                    Log.e("MainActivity", "Face detection failed", e)
                    faceResults.value = null
                    safeComplete()
                }
        } catch (e: Exception) {
            Log.e("MainActivity", "Face exception", e)
            faceResults.value = null
            safeComplete()
        }
    }

    @OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UseeTheme {
                val navController: NavHostController = rememberAnimatedNavController()
                val isShowBottomBar = remember { mutableStateOf(false) }
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Scaffold(
                        bottomBar = {
                            if (isShowBottomBar.value)
                                BottomNavigationBar(navController = navController)
                        },
                        content = { padding ->
                            AppScreen(
                                isShowBottomBar = isShowBottomBar,
                                navController = navController,
                                padding = padding,
                                mainActivity = this
                            )
                        }
                    )
                }
            }
        }

        // Получаем разрешения камеры
        requestCameraPermission()

        // Проверяем и запрашиваем разрешения на запись
        checkAndRequestWritePermission()

        outputDirectory = findOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    external fun convertVRMtoGLBcpp(inputPath: String, newPath: String): String

    @Composable
    fun BottomNavigationBar(navController: NavHostController) {
        NavigationBar() {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route
            Constants.BottomNavItems.forEach { navItem ->
                NavigationBarItem(
                    selected = currentRoute == navItem.route,
                    onClick = { navController.navigate(navItem.route) },
                    icon = {
                        Icon(
                            imageVector = navItem.icon,
                            contentDescription = navItem.label,
                            tint = if (currentRoute == navItem.route) blueDark7 else blue7
                        )
                    },
                    alwaysShowLabel = false
                )
            }
        }
    }

    @OptIn(ExperimentalAnimationApi::class)
    @Composable
    fun AppScreen(
        navController: NavHostController,
        padding: PaddingValues,
        isShowBottomBar: MutableState<Boolean>,
        mainActivity: MainActivity
    ) {
        AnimatedNavHost(
            modifier = Modifier.padding(paddingValues = padding),
            navController = navController,
            startDestination = "welcomScreen",
            builder = {
                composable(route = "welcomScreen") {
                    WelcomScreen(navController)
                    isShowBottomBar.value = false
                }
                composable(route = "characterScreen") {
                    CharacterScreen(mainActivity)
                    isShowBottomBar.value = true
                }
//                composable(route = "galleryScreen") {
//                    GalleryScreen()
//                    isShowBottomBar.value = true
//                }
                composable(route = "cameraScreen") {
                    CameraScreen(mainActivity)
                    isShowBottomBar.value = true
                }
            })
    }
}