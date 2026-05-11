package com.example.useevtubingapp

import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.filament.View
import com.google.android.filament.utils.ModelViewer
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class ModelRenderer {
    companion object {
        private const val TAG = "ModelRenderer"
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var lifecycle: Lifecycle
    private lateinit var choreographer: Choreographer
    private lateinit var modelViewer: ModelViewer

    private val frameScheduler = FrameCallback()

    private var isModelReady = false
    private var isLoading = false
    private var onModelReadyCallback: (() -> Unit)? = null

    private var isModelVisible = true

    private val bindPoseCache = mutableMapOf<String, FloatArray>()
    private val boneEntityCache = mutableMapOf<String, Int>()
    private var rootTransformInstance: Int = 0

    private var bodyRotationY = 180f
    private var bodyRotationX = 0f
    private var bodyPositionY = 0f
    private var bodyScale = 0.8f
    private val smoothingFactor = 0.3f
    private var targetBodyRotationY = 180f
    private var targetBodyRotationX = 0f
    private var targetBodyPositionY = 0f

    // Для стабилизации масштаба
    private var stableScale = 0.8f
    private val scaleSmoothingFactor = 0.15f  // Очень плавное изменение масштаба
    private val minScale = 0.3f
    private val maxScale = 2.0f

    // Для отслеживания размера человека
    private var basePersonHeight = 0f
    private var isBasePersonHeightSet = false
    private val personHeightHistory = mutableListOf<Float>()
    //private val maxHistorySize = 10

    //private var frameCaptureCallback: ((Bitmap) -> Unit)? = null
    private var isFrameCaptureEnabled = false
    private var lastCaptureTime = 0L
    private val CAPTURE_INTERVAL_MS = 33L

    private var recordingCallback: ((Bitmap) -> Unit)? = null

    private val boneRotations = mutableMapOf<String, Vector3D>()

    private val boneNameMapping = mapOf(
        "LeftShoulder"  to listOf("J_Bip_L_Shoulder",  "LeftShoulder",  "L_Shoulder"),
        "RightShoulder" to listOf("J_Bip_R_Shoulder",  "RightShoulder", "R_Shoulder"),
        "LeftHip"       to listOf("J_Bip_L_UpperLeg",  "LeftHip",       "L_Hip"),
        "RightHip"      to listOf("J_Bip_R_UpperLeg",  "RightHip",      "R_Hip"),
        "LeftElbow"     to listOf("J_Bip_L_LowerArm",  "LeftElbow",     "L_Elbow"),
        "RightElbow"    to listOf("J_Bip_R_LowerArm",  "RightElbow",    "R_Elbow"),
        "LeftWrist"     to listOf("J_Bip_L_Hand",       "LeftWrist",     "L_Wrist"),
        "RightWrist"    to listOf("J_Bip_R_Hand",       "RightWrist",    "R_Wrist"),
        "LeftKnee"      to listOf("J_Bip_L_LowerLeg",  "LeftKnee",      "L_Knee"),
        "RightKnee"     to listOf("J_Bip_R_LowerLeg",  "RightKnee",     "R_Knee"),
        "LeftAnkle"     to listOf("J_Bip_L_Foot",      "LeftAnkle",     "L_Ankle"),
        "RightAnkle"    to listOf("J_Bip_R_Foot",      "RightAnkle",    "R_Ankle"),
        "Head"          to listOf("J_Bip_C_Head",       "Head"),
        "Neck"          to listOf("J_Bip_C_Neck",       "Neck"),
        "Spine"         to listOf("J_Bip_C_Spine",      "Spine")
    )

    init {
        boneNameMapping.keys.forEach { boneRotations[it] = Vector3D() }
    }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            if (::choreographer.isInitialized) choreographer.postFrameCallback(frameScheduler)
        }
        override fun onPause(owner: LifecycleOwner) {
            if (::choreographer.isInitialized) choreographer.removeFrameCallback(frameScheduler)
        }
        override fun onDestroy(owner: LifecycleOwner) {
            if (::choreographer.isInitialized) choreographer.removeFrameCallback(frameScheduler)
            lifecycle.removeObserver(this)
            cleanup()
        }
    }

    //функции для покадровой отрисовки видео
    fun startFrameCapture(callback: (Bitmap) -> Unit) {
        recordingCallback = callback
        isFrameCaptureEnabled = true
    }

    //Остановить захват кадров
    fun stopFrameCapture() {
        isFrameCaptureEnabled = false
        recordingCallback = null
    }

    //Захватить текущий кадр (вызывается в loop)
    fun captureCurrentFrame() {
        if (!isFrameCaptureEnabled || !isModelReady) return

        val width = surfaceView.width
        val height = surfaceView.height

        if (width <= 0 || height <= 0) return

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            android.view.PixelCopy.request(
                surfaceView,
                bitmap,
                { copyResult ->
                    if (copyResult == android.view.PixelCopy.SUCCESS) {
                        recordingCallback?.invoke(bitmap)
                    } else {
                        bitmap.recycle()
                    }
                },
                Handler(Looper.getMainLooper())
            )
        }
    }

    fun onSurfaceAvailable(surfaceView: SurfaceView, lifecycle: Lifecycle, file: File) {
        choreographer = Choreographer.getInstance()
        this.surfaceView = surfaceView
        this.lifecycle = lifecycle
        lifecycle.addObserver(lifecycleObserver)

        modelViewer = ModelViewer(surfaceView = surfaceView)
        surfaceView.setOnTouchListener { _, event -> modelViewer.onTouchEvent(event); true }
        setupScene()
        loadModelAsync(file)
    }

    fun setOnModelReadyListener(callback: () -> Unit) {
        onModelReadyCallback = callback
        if (isModelReady) callback()
    }

    private fun loadModelAsync(file: File) {
        if (isLoading) return
        isLoading = true
        Thread {
            try {
                val bytes = file.readBytes()
                val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
                    order(ByteOrder.nativeOrder())
                    put(bytes)
                    rewind()
                }
                Handler(Looper.getMainLooper()).post {
                    try {
                        modelViewer.loadModelGlb(buffer)
                        modelViewer.transformToUnitCube()
                        onModelLoaded()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading model on main thread", e)
                        isLoading = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read file", e)
                isLoading = false
            }
        }.start()
    }

    private fun onModelLoaded() {
        val asset = modelViewer.asset ?: run {
            Log.e(TAG, "Asset is null after loading")
            isLoading = false
            return
        }

        val tm = modelViewer.engine.transformManager
        rootTransformInstance = tm.getInstance(asset.root).takeIf { it != 0 }
            ?: asset.entities.firstOrNull { tm.getInstance(it) != 0 }?.let { tm.getInstance(it) } ?: 0

        cacheBones(asset)

        isModelReady = true
        isLoading = false
        applyInitialSettings()

        Log.d(TAG, "Model ready. Bones cached: ${boneEntityCache.size}")
        onModelReadyCallback?.invoke()
    }

    private fun cacheBones(asset: com.google.android.filament.gltfio.FilamentAsset) {
        boneEntityCache.clear()
        bindPoseCache.clear()
        val tm = modelViewer.engine.transformManager

        for ((logicalName, possibleNames) in boneNameMapping) {
            for (name in possibleNames) {
                val entity = asset.getFirstEntityByName(name)
                if (entity != 0) {
                    val instance = tm.getInstance(entity)
                    if (instance != 0) {
                        boneEntityCache[logicalName] = entity
                        val bindMat = FloatArray(16)
                        tm.getTransform(instance, bindMat)
                        bindPoseCache[logicalName] = bindMat
                        break
                    }
                }
            }
        }
    }

    private fun applyAllTransformations() {
        val tm = modelViewer.engine.transformManager

        for ((logicalName, angles) in boneRotations) {
            val entity = boneEntityCache[logicalName] ?: continue
            val instance = tm.getInstance(entity)
            if (instance == 0) continue
            val bindMat = bindPoseCache[logicalName] ?: continue

            val rotMat = eulerToMatrix(angles.x, angles.y, angles.z)
            rotMat[12] = bindMat[12]
            rotMat[13] = bindMat[13]
            rotMat[14] = bindMat[14]
            tm.setTransform(instance, rotMat)
        }

        val currentScale = if (isModelVisible) bodyScale else 1f
        setBodyTransform(bodyRotationX, bodyRotationY, currentScale, bodyPositionY)
        modelViewer.animator?.updateBoneMatrices()
    }

    private fun setBodyTransform(rotX: Float, rotY: Float, scale: Float, posY: Float) {
        if (rootTransformInstance == 0) {
            Log.e(TAG, "setBodyTransform: rootTransformInstance == 0, cannot apply scale")
            return
        }
        val tm = modelViewer.engine.transformManager

        val adjustedRotY = if (isModelVisible) 180f else rotY
        val adjustedRotX = if (isModelVisible) 0f else rotX
        val adjustedRollZ = 0f

        val m = eulerToMatrix(adjustedRotX, adjustedRotY, adjustedRollZ)
        m[0] *= scale; m[1] *= scale; m[2] *= scale
        m[4] *= scale; m[5] *= scale; m[6] *= scale
        m[8] *= scale; m[9] *= scale; m[10] *= scale
        m[12] = 0f; m[13] = posY; m[14] = 0f; m[15] = 1f

        tm.setTransform(rootTransformInstance, m)
        Log.d(TAG, "setBodyTransform: scale = $scale, posY = $posY, rotX = $adjustedRotX, rotY = $adjustedRotY, rollZ = $adjustedRollZ")
    }

    private fun eulerToMatrix(pitchDeg: Float, yawDeg: Float, rollDeg: Float): FloatArray {
        val p = pitchDeg * PI.toFloat() / 180f
        val y = yawDeg   * PI.toFloat() / 180f
        val r = rollDeg  * PI.toFloat() / 180f
        val cp = cos(p); val sp = sin(p)
        val cy = cos(y); val sy = sin(y)
        val cr = cos(r); val sr = sin(r)
        return floatArrayOf(
            cy*cr,           cy*sr,           -sy,     0f,
            sp*sy*cr-cp*sr,  sp*sy*sr+cp*cr,  sp*cy,  0f,
            cp*sy*cr+sp*sr,  cp*sy*sr-sp*cr,  cp*cy,  0f,
            0f, 0f, 0f, 1f
        )
    }

    fun normalizeBodyTransform() {
        val scaleNow = bodyScale
        val posY = bodyPositionY
        if (rootTransformInstance == 0) {
            Log.e(TAG, "setBodyTransform: rootTransformInstance == 0, cannot apply scale")
            return
        }
        val tm = modelViewer.engine.transformManager

        val m = eulerToMatrix(0f, 180f, 0f)
        m[0] *= scaleNow; m[1] *= scaleNow; m[2] *= scaleNow
        m[4] *= scaleNow; m[5] *= scaleNow; m[6] *= scaleNow
        m[8] *= scaleNow; m[9] *= scaleNow; m[10] *= scaleNow
        m[12] = 0f; m[13] = posY; m[14] = 0f; m[15] = 1f

        tm.setTransform(rootTransformInstance, m)
    }

    fun resetPose() {
        if (!isModelReady) return
        for (key in boneRotations.keys) {
            boneRotations[key] = Vector3D()
        }
        bodyPositionY = 0f
        targetBodyPositionY = 0f
        bodyRotationX = 0f
        targetBodyRotationX = 0f
        bodyRotationY = 180f
        targetBodyRotationY = 180f
        applyAllTransformations()
    }

    private fun setupScene() {
        modelViewer.scene.skybox = null
        modelViewer.view.blendMode = View.BlendMode.TRANSLUCENT
        modelViewer.renderer.clearOptions = modelViewer.renderer.clearOptions.apply {
            clear = true
            clearColor = floatArrayOf(0f, 0f, 0f, 0f)
        }
    }

    private fun applyInitialSettings() {
        bodyScale = 0.5f
        stableScale = 0.5f
        bodyRotationY = 180f
        targetBodyRotationY = 180f
        isModelVisible = true
        applyAllTransformations()
    }

    fun setInitialTransform(scale: Float = 0.5f, rotationY: Float = 180f, posY : Float = -1.8f) {
        bodyScale = scale
        stableScale = scale
        bodyRotationY = rotationY
        targetBodyRotationY = rotationY
        bodyPositionY = posY
        isModelVisible = true
        if (isModelReady) applyAllTransformations()
    }

    fun applyPoseToGLB(pose: Pose, imageWidth: Float, imageHeight: Float) {
        if (!isModelReady) return
        resetPose()
        adjustScaleByPose(pose, imageHeight)
        updateBodyParams(pose, imageWidth, imageHeight)
        updateRotationsFromPose(pose)
        applyAllTransformations()
    }

    fun applyFaceToGLB(face: Face) {
        if (!isModelReady) return
    }

    private fun isLandmarkVisible(pose: Pose, landmarkType: Int): Boolean {
        val landmark = pose.getPoseLandmark(landmarkType) ?: return false
        return landmark.inFrameLikelihood > 0.3f
    }

    private fun get3D(pose: Pose, landmarkType: Int): Triple<Float, Float, Float>? {
        val landmark = pose.getPoseLandmark(landmarkType) ?: return null
        return Triple(landmark.position3D.x, landmark.position3D.z, landmark.position3D.y)
    }

    private fun updateBodyParams(pose: Pose, imageWidth: Float, imageHeight: Float) {
        if (imageWidth <= 0f || imageHeight <= 0f) return

        val lSh  = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rSh  = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val lHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val lEye = pose.getPoseLandmark(PoseLandmark.LEFT_EYE)
        val rEye = pose.getPoseLandmark(PoseLandmark.RIGHT_EYE)

        // Вращение тела (Y)
        if (lSh != null && rSh != null) {
            val dx = lSh.position.x - rSh.position.x
            val dy = lSh.position.y - rSh.position.y
            targetBodyRotationY = -atan2(dy, dx) * 180f / PI.toFloat() * 0.6f
        }

        // Наклон тела (X)
        if (lSh != null && lHip != null) {
            val dx = lSh.position.x - lHip.position.x
            val dy = lSh.position.y - lHip.position.y
            targetBodyRotationX = (atan2(dy, dx) * 180f / PI.toFloat() - 90f) * 0.5f
        }

        val eyeY = when {
            lEye != null && rEye != null -> (lEye.position.y + rEye.position.y) / 2f
            lEye != null -> lEye.position.y
            rEye != null -> rEye.position.y
            else -> null
        }

        if (eyeY != null) {
            val centerY = imageHeight / 2f
            val pixelOffset = centerY - eyeY

            // Коэффициент сколько мировых единиц на пиксель
            val pixelsToWorldUnits = 0.06f * bodyScale

            targetBodyPositionY = pixelOffset * pixelsToWorldUnits

            Log.d(TAG, "POS Y: eyeY=$eyeY, centerY=$centerY, " +
                    "pixelOffset=$pixelOffset, target=$targetBodyPositionY")
        } else {
            targetBodyPositionY = lerp(targetBodyPositionY, 0f, 0.1f)
        }
        // Сглаживание
        bodyRotationY = lerp(bodyRotationY, targetBodyRotationY, smoothingFactor)
        bodyRotationX = lerp(bodyRotationX, targetBodyRotationX, smoothingFactor)
        bodyPositionY = lerp(bodyPositionY, targetBodyPositionY, smoothingFactor)
    }



    private fun updateRotationsFromPose(pose: Pose) {
        if (isLandmarkVisible(pose, PoseLandmark.LEFT_SHOULDER) && isLandmarkVisible(pose, PoseLandmark.LEFT_ELBOW)) {
            val lSh3D = get3D(pose, PoseLandmark.LEFT_SHOULDER)!!
            val lEl3D = get3D(pose, PoseLandmark.LEFT_ELBOW)!!
            boneRotations["LeftShoulder"] = Vector3D(z = -angleBetween3D(lSh3D, lEl3D))
            if (isLandmarkVisible(pose, PoseLandmark.LEFT_WRIST)) {
                val lWr3D = get3D(pose, PoseLandmark.LEFT_WRIST)!!
                boneRotations["LeftElbow"] = Vector3D(z = -angleBetween3D(lEl3D, lWr3D))
            }
        }

        if (isLandmarkVisible(pose, PoseLandmark.RIGHT_SHOULDER) && isLandmarkVisible(pose, PoseLandmark.RIGHT_ELBOW)) {
            val rSh3D = get3D(pose, PoseLandmark.RIGHT_SHOULDER)!!
            val rEl3D = get3D(pose, PoseLandmark.RIGHT_ELBOW)!!
            boneRotations["RightShoulder"] = Vector3D(z = angleBetween3D(rSh3D, rEl3D))
            if (isLandmarkVisible(pose, PoseLandmark.RIGHT_WRIST)) {
                val rWr3D = get3D(pose, PoseLandmark.RIGHT_WRIST)!!
                boneRotations["RightElbow"] = Vector3D(z = angleBetween3D(rEl3D, rWr3D))
            }
        }

        if (isLandmarkVisible(pose, PoseLandmark.LEFT_HIP) && isLandmarkVisible(pose, PoseLandmark.LEFT_KNEE)) {
            val lHi3D = get3D(pose, PoseLandmark.LEFT_HIP)!!
            val lKn3D = get3D(pose, PoseLandmark.LEFT_KNEE)!!
            boneRotations["LeftHip"] = Vector3D(z = angleBetween3D(lHi3D, lKn3D))
            if (isLandmarkVisible(pose, PoseLandmark.LEFT_ANKLE)) {
                val lAn3D = get3D(pose, PoseLandmark.LEFT_ANKLE)!!
                boneRotations["LeftKnee"] = Vector3D(z = -angleBetween3D(lKn3D, lAn3D))
            }
        }

        if (isLandmarkVisible(pose, PoseLandmark.RIGHT_HIP) && isLandmarkVisible(pose, PoseLandmark.RIGHT_KNEE)) {
            val rHi3D = get3D(pose, PoseLandmark.RIGHT_HIP)!!
            val rKn3D = get3D(pose, PoseLandmark.RIGHT_KNEE)!!
            boneRotations["RightHip"] = Vector3D(z = -angleBetween3D(rHi3D, rKn3D))
            if (isLandmarkVisible(pose, PoseLandmark.RIGHT_ANKLE)) {
                val rAn3D = get3D(pose, PoseLandmark.RIGHT_ANKLE)!!
                boneRotations["RightKnee"] = Vector3D(z = angleBetween3D(rKn3D, rAn3D))
            }
        }

        if (isLandmarkVisible(pose, PoseLandmark.LEFT_EYE) && isLandmarkVisible(pose, PoseLandmark.RIGHT_EYE) &&
            isLandmarkVisible(pose, PoseLandmark.NOSE)) {
            val lEye3D = get3D(pose, PoseLandmark.LEFT_EYE)!!
            val rEye3D = get3D(pose, PoseLandmark.RIGHT_EYE)!!
            val nose3D = get3D(pose, PoseLandmark.NOSE)!!

            val eyeMidX = (lEye3D.first + rEye3D.first) / 2f
            val eyeMidY = (lEye3D.second + rEye3D.second) / 2f
            val eyeMidZ = (lEye3D.third + rEye3D.third) / 2f

            val dirX = nose3D.first - eyeMidX
            val dirY = nose3D.second - eyeMidY
            val dirZ = nose3D.third - eyeMidZ

            val rotX = atan2(dirY, sqrt(dirX * dirX + dirZ * dirZ)) * 180f / PI.toFloat() * 0.5f
            val rotY = atan2(dirX, dirZ) * 180f / PI.toFloat() * 0.5f

            val eyeDx = rEye3D.first - lEye3D.first
            val eyeDy = rEye3D.second - lEye3D.second
            val eyeDz = rEye3D.third - lEye3D.third
            val rotZ = atan2(eyeDy, sqrt(eyeDx * eyeDx + eyeDz * eyeDz)) * 180f / PI.toFloat() * 0.3f

            boneRotations["Head"] = Vector3D(x = rotX, y = rotY, z = rotZ)
        } else if (isLandmarkVisible(pose, PoseLandmark.NOSE) &&
            isLandmarkVisible(pose, PoseLandmark.LEFT_EAR) &&
            isLandmarkVisible(pose, PoseLandmark.RIGHT_EAR)) {
            val nose = pose.getPoseLandmark(PoseLandmark.NOSE)!!
            val lEar = pose.getPoseLandmark(PoseLandmark.LEFT_EAR)!!
            val rEar = pose.getPoseLandmark(PoseLandmark.RIGHT_EAR)!!
            val midX = (lEar.position.x + rEar.position.x) / 2f
            val midY = (lEar.position.y + rEar.position.y) / 2f
            boneRotations["Head"] = Vector3D(
                x = (nose.position.y - midY) * 20f,
                y = (nose.position.x - midX) * 30f
            )
        }
    }

    private fun angleBetween3D(parent: Triple<Float, Float, Float>, child: Triple<Float, Float, Float>): Float {
        val dx = child.first - parent.first
        val dy = child.second - parent.second
        val dz = child.third - parent.third

        val downVector = Triple(0f, -1f, 0f)
        val boneVector = Triple(dx, dy, dz)

        val dot = boneVector.first * downVector.first + boneVector.second * downVector.second + boneVector.third * downVector.third
        val mag1 = sqrt(boneVector.first * boneVector.first + boneVector.second * boneVector.second + boneVector.third * boneVector.third)
        if (mag1 == 0f) return 0f
        return acos((dot / mag1).coerceIn(-1f, 1f)) * 180f / PI.toFloat()
    }

    fun adjustScaleByPose(pose: Pose, imageHeight: Float, desiredFillRatio: Float = 0.78f) {
        if (!isModelReady || imageHeight <= 0f) return

        val headPoint = getHeadPoint(pose)
        val shoulderPoint = getShoulderCenter(pose)

        if (headPoint == null || shoulderPoint == null) {
            // Если не можем определить позу, используем последний стабильный масштаб
            bodyScale = lerp(bodyScale, stableScale, scaleSmoothingFactor)
            return
        }

        // Вычисляем текущую высоту человека в пикселях
        val headToShoulderDistance = abs(headPoint.y - shoulderPoint.y)

        // Также вычисляем полную высоту тела если доступны ключевые точки
        var fullBodyHeight = headToShoulderDistance * 3f

        val leftAnkle = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)?.position
        val rightAnkle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)?.position

        if (leftAnkle != null && rightAnkle != null) {
            val ankleCenterY = (leftAnkle.y + rightAnkle.y) / 2f
            fullBodyHeight = abs(headPoint.y - ankleCenterY)
        } else if (leftAnkle != null) {
            fullBodyHeight = abs(headPoint.y - leftAnkle.y)
        } else if (rightAnkle != null) {
            fullBodyHeight = abs(headPoint.y - rightAnkle.y)
        }

        // Сохраняем базовую высоту человека при первой возможности
        if (!isBasePersonHeightSet && fullBodyHeight > 0) {
            // Устанавливаем базовую высоту на основе среднего размера человека в кадре
            personHeightHistory.add(fullBodyHeight)
            if (personHeightHistory.size >= 5) {
                basePersonHeight = personHeightHistory.average().toFloat()
                isBasePersonHeightSet = true
                stableScale = 0.8f
                Log.d(TAG, "Base person height set: $basePersonHeight")
            }
        }

        if (isBasePersonHeightSet && basePersonHeight > 0) {
            val heightRatio = fullBodyHeight / basePersonHeight
            val targetScale = stableScale * heightRatio

            // Ограничиваем масштаб
            val clampedScale = targetScale.coerceIn(minScale, maxScale)

            // Плавно изменяем масштаб
            bodyScale = lerp(bodyScale, clampedScale, scaleSmoothingFactor)

            // Также плавно обновляем стабильный масштаб
            stableScale = lerp(stableScale, clampedScale, scaleSmoothingFactor * 0.5f)

            Log.d(TAG, "Scale: ratio=$heightRatio, target=$targetScale, clamped=$clampedScale, current=$bodyScale")
        } else {
            val targetScale = (imageHeight * desiredFillRatio / headToShoulderDistance) * (10f / 3f)
            val clampedScale = targetScale.coerceIn(minScale, maxScale)
            bodyScale = lerp(bodyScale, clampedScale, scaleSmoothingFactor)
        }

        isModelVisible = true
    }

    fun hideModel() {
        if (!isModelReady) return
        isModelVisible = false
        applyAllTransformations()
        Log.d(TAG, "Model hidden")
    }

    private fun getHeadPoint(pose: Pose): PointF? {
        val nose = pose.getPoseLandmark(PoseLandmark.NOSE)?.position
        if (nose != null) return nose
        val leftEar = pose.getPoseLandmark(PoseLandmark.LEFT_EAR)?.position
        val rightEar = pose.getPoseLandmark(PoseLandmark.RIGHT_EAR)?.position
        return when {
            leftEar != null && rightEar != null -> PointF((leftEar.x + rightEar.x) / 2f, (leftEar.y + rightEar.y) / 2f)
            leftEar != null -> leftEar
            rightEar != null -> rightEar
            else -> null
        }
    }

    private fun getShoulderCenter(pose: Pose): PointF? {
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)?.position
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)?.position
        return when {
            leftShoulder != null && rightShoulder != null -> PointF((leftShoulder.x + rightShoulder.x) / 2f, (leftShoulder.y + rightShoulder.y) / 2f)
            leftShoulder != null -> leftShoulder
            rightShoulder != null -> rightShoulder
            else -> null
        }
    }

    private fun angleBetween(a: PointF, b: PointF, c: PointF): Float {
        val ba = PointF(a.x - b.x, a.y - b.y)
        val bc = PointF(c.x - b.x, c.y - b.y)
        val dot = ba.x * bc.x + ba.y * bc.y
        val mag = sqrt(ba.x * ba.x + ba.y * ba.y) * sqrt(bc.x * bc.x + bc.y * bc.y)
        if (mag == 0f) return 0f
        return acos((dot / mag).coerceIn(-1f, 1f)) * 180f / PI.toFloat()
    }

    private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t

    fun isReady(): Boolean = isModelReady

    private fun cleanup() {
        if (::modelViewer.isInitialized) modelViewer.destroyModel()
        isModelReady = false
    }

    inner class FrameCallback : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            try {
                if (::choreographer.isInitialized) {
                    choreographer.postFrameCallback(this)
                }

                if (::modelViewer.isInitialized && isModelReady) {
                    // Рендерим модель
                    modelViewer.render(frameTimeNanos)

                    // Захватываем кадр для видео
                    if (isFrameCaptureEnabled) {
                        val currentTime = System.currentTimeMillis()
                        if (lastCaptureTime == 0L || (currentTime - lastCaptureTime) >= CAPTURE_INTERVAL_MS) {
                            captureCurrentFrame()
                            lastCaptureTime = currentTime
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "FrameCallback error", e)
            }
        }
    }
}

data class Vector3D(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f)