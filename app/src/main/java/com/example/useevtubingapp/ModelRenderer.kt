package com.example.useevtubingapp

import android.graphics.PointF
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

    private var isModelVisible = true  // Флаг видимости

    private val bindPoseCache = mutableMapOf<String, FloatArray>()
    private val boneEntityCache = mutableMapOf<String, Int>()
    private var rootTransformInstance: Int = 0

    private var bodyRotationY = 0f
    private var bodyRotationX = 0f
    private var bodyPositionY = 0f
    private var bodyScale = 0.8f
    private val smoothingFactor = 0.3f
    private var targetBodyRotationY = 0f
    private var targetBodyRotationX = 0f
    private var targetBodyPositionY = 0f

    private var targetBodyScale = 0.8f
    private val scaleSmoothingFactor = 0.25f

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
                android.os.Handler(android.os.Looper.getMainLooper()).post {
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

        Log.d(TAG, "Animator: ${modelViewer.animator}, animations: ${modelViewer.animator?.animationCount ?: 0}")

        val allNames = asset.entities.toList().mapNotNull { asset.getName(it) }.sorted()
        Log.d(TAG, "All entity names: ${allNames.joinToString(", ")}")

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
                        Log.d(TAG, "Cached (exact) $logicalName -> '$name'")
                        break
                    }
                }
            }
        }

        val missing = boneNameMapping.keys.filter { !boneEntityCache.containsKey(it) }
        if (missing.isNotEmpty()) {
            Log.d(TAG, "Trying partial match for: $missing")
            for (entity in asset.entities) {
                val entityName = asset.getName(entity) ?: continue
                val instance = tm.getInstance(entity)
                if (instance == 0) continue
                for (logicalName in missing) {
                    if (boneEntityCache.containsKey(logicalName)) continue
                    val patterns = boneNameMapping[logicalName] ?: continue
                    if (patterns.any { entityName.contains(it, ignoreCase = true) }) {
                        boneEntityCache[logicalName] = entity
                        val bindMat = FloatArray(16)
                        tm.getTransform(instance, bindMat)
                        bindPoseCache[logicalName] = bindMat
                        Log.d(TAG, "Cached (partial) $logicalName -> '$entityName'")
                        break
                    }
                }
            }
        }

        Log.d(TAG, "=== Bone cache result ===")
        boneNameMapping.keys.forEach { name ->
            if (boneEntityCache.containsKey(name)) Log.d(TAG, "  OK  $name")
            else Log.w(TAG, "  MISSING $name")
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

        val currentScale = if (isModelVisible) bodyScale else 0.01f
        setBodyTransform(bodyRotationX, bodyRotationY, currentScale, bodyPositionY)

        modelViewer.animator?.updateBoneMatrices()
    }

    private fun setBodyTransform(rotX: Float, rotY: Float, scale: Float, posY: Float) {
        if (rootTransformInstance == 0) return
        val tm = modelViewer.engine.transformManager

        val m = eulerToMatrix(rotX, rotY, 0f)
        m[0] *= scale; m[1] *= scale; m[2] *= scale
        m[4] *= scale; m[5] *= scale; m[6] *= scale
        m[8] *= scale; m[9] *= scale; m[10] *= scale
        m[12] = 0f; m[13] = posY; m[14] = 0f; m[15] = 1f

        tm.setTransform(rootTransformInstance, m)
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
        targetBodyScale = 0.5f
        bodyRotationY = 180f
        targetBodyRotationY = 180f
        isModelVisible = true
        setBodyTransform(bodyRotationX, bodyRotationY, bodyScale, bodyPositionY)
        modelViewer.animator?.updateBoneMatrices()
        Log.d(TAG, "Initial settings applied")
    }

    // Публичные методы

    fun setInitialTransform(scale: Float = 0.5f, rotationY: Float = 180f) {
        bodyScale = scale
        targetBodyScale = scale
        bodyRotationY = rotationY
        targetBodyRotationY = rotationY
        isModelVisible = true
        if (isModelReady) {
            setBodyTransform(bodyRotationX, bodyRotationY, bodyScale, bodyPositionY)
            modelViewer.animator?.updateBoneMatrices()
        }
        Log.d(TAG, "Initial transform: scale=$scale, rotationY=$rotationY")
    }

//    fun setInitialScale(scale: Float) {
//        if (scale <= 0f) return
//        bodyScale = scale
//        targetBodyScale = scale
//        isModelVisible = true
//        if (isModelReady) {
//            setBodyTransform(bodyRotationX, bodyRotationY, bodyScale, bodyPositionY)
//            modelViewer.animator?.updateBoneMatrices()
//        }
//        Log.d(TAG, "Initial scale set to: $scale")
//    }
//
//    fun setModelScale(scale: Float) {
//        if (scale <= 0f) return
//        targetBodyScale = scale.coerceIn(0.3f, 3.0f)
//        isModelVisible = true
//        if (isModelReady) {
//            setBodyTransform(bodyRotationX, bodyRotationY, bodyScale, bodyPositionY)
//            modelViewer.animator?.updateBoneMatrices()
//        }
//        Log.d(TAG, "Model scale set to: $targetBodyScale")
//    }
//
//    fun flipModel180Degrees() {
//        bodyRotationY = 180f
//        targetBodyRotationY = 180f
//        if (isModelReady) {
//            setBodyTransform(bodyRotationX, bodyRotationY, bodyScale, bodyPositionY)
//            modelViewer.animator?.updateBoneMatrices()
//        }
//        Log.d(TAG, "Model flipped 180 degrees")
//    }

    fun applyPoseToGLB(pose: Pose, imageWidth: Float, imageHeight: Float) {
        if (!isModelReady) return
        adjustScaleByPose(pose, imageHeight)
        updateBodyParams(pose, imageWidth, imageHeight)
        updateRotationsFromPose(pose)
        applyAllTransformations()
    }

    fun applyFaceToGLB(face: Face) {
        if (!isModelReady) return
        // TODO: блендшейпы лица
    }

    private fun updateBodyParams(pose: Pose, imageWidth: Float, imageHeight: Float) {
        if (imageWidth <= 0f || imageHeight <= 0f) return
        val lSh  = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rSh  = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val lHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val rHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
        val nose = pose.getPoseLandmark(PoseLandmark.NOSE)

        if (lSh != null && rSh != null) {
            val dx = lSh.position.x - rSh.position.x
            val dy = lSh.position.y - rSh.position.y
            targetBodyRotationY = -atan2(dy, dx) * 180f / PI.toFloat() * 0.6f
        }
        if (lSh != null && lHip != null) {
            val dx = lSh.position.x - lHip.position.x
            val dy = lSh.position.y - lHip.position.y
            targetBodyRotationX = (atan2(dy, dx) * 180f / PI.toFloat() - 90f) * 0.5f
        }
        if (nose != null && lHip != null && rHip != null) {
            val hipCenterY = (lHip.position.y + rHip.position.y) / 2f
            val bodyHeight = abs(nose.position.y - hipCenterY)
            targetBodyPositionY = -((1f - bodyHeight / imageHeight) * 0.5f)
        }

        bodyRotationY = lerp(bodyRotationY, targetBodyRotationY, smoothingFactor)
        bodyRotationX = lerp(bodyRotationX, targetBodyRotationX, smoothingFactor)
        bodyPositionY = lerp(bodyPositionY, targetBodyPositionY, smoothingFactor)

        bodyScale = lerp(bodyScale, targetBodyScale, scaleSmoothingFactor)
    }

    private fun updateRotationsFromPose(pose: Pose) {
        val lSh = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val lEl = pose.getPoseLandmark(PoseLandmark.LEFT_ELBOW)
        val lWr = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
        if (lSh != null && lEl != null && lWr != null) {
            boneRotations["LeftShoulder"] = Vector3D(z = -angleBetween(lSh.position, lEl.position, PointF(lEl.position.x, lEl.position.y - 20f)))
            boneRotations["LeftElbow"]    = Vector3D(z = -angleBetween(lEl.position, lWr.position, PointF(lWr.position.x, lWr.position.y - 20f)))
        }

        val rSh = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
        val rEl = pose.getPoseLandmark(PoseLandmark.RIGHT_ELBOW)
        val rWr = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
        if (rSh != null && rEl != null && rWr != null) {
            boneRotations["RightShoulder"] = Vector3D(z = angleBetween(rSh.position, rEl.position, PointF(rEl.position.x, rEl.position.y - 20f)))
            boneRotations["RightElbow"]    = Vector3D(z = angleBetween(rEl.position, rWr.position, PointF(rWr.position.x, rWr.position.y - 20f)))
        }

        val lHi = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val lKn = pose.getPoseLandmark(PoseLandmark.LEFT_KNEE)
        val lAn = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
        if (lHi != null && lKn != null && lAn != null) {
            boneRotations["LeftHip"]  = Vector3D(z =  angleBetween(lHi.position, lKn.position, PointF(lKn.position.x, lKn.position.y - 20f)))
            boneRotations["LeftKnee"] = Vector3D(z = -angleBetween(lKn.position, lAn.position, PointF(lAn.position.x, lAn.position.y - 20f)))
        }

        val rHi = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
        val rKn = pose.getPoseLandmark(PoseLandmark.RIGHT_KNEE)
        val rAn = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)
        if (rHi != null && rKn != null && rAn != null) {
            boneRotations["RightHip"]  = Vector3D(z = -angleBetween(rHi.position, rKn.position, PointF(rKn.position.x, rKn.position.y - 20f)))
            boneRotations["RightKnee"] = Vector3D(z =  angleBetween(rKn.position, rAn.position, PointF(rAn.position.x, rAn.position.y - 20f)))
        }

        val nose = pose.getPoseLandmark(PoseLandmark.NOSE)
        val lEar = pose.getPoseLandmark(PoseLandmark.LEFT_EAR)
        val rEar = pose.getPoseLandmark(PoseLandmark.RIGHT_EAR)
        if (nose != null && lEar != null && rEar != null) {
            val midX = (lEar.position.x + rEar.position.x) / 2f
            val midY = (lEar.position.y + rEar.position.y) / 2f
            boneRotations["Head"] = Vector3D(
                x = (nose.position.y - midY) * 20f,
                y = (nose.position.x - midX) * 30f
            )
        }
    }

    fun adjustScaleByPose(pose: Pose, imageHeight: Float, desiredFillRatio: Float = 0.78f) {
        if (!isModelReady || imageHeight <= 0f) return

        val headPoint = getHeadPoint(pose)
        val shoulderPoint = getShoulderCenter(pose)

        if (headPoint == null || shoulderPoint == null) {
            return
        }

        val headToShoulderDistance = abs(headPoint.y - shoulderPoint.y)

        if (headToShoulderDistance < 25f || headToShoulderDistance > imageHeight * 0.85f) {
            return
        }

        val newTargetScale = (imageHeight * desiredFillRatio / headToShoulderDistance).coerceIn(0.35f, 2.5f)
        targetBodyScale = newTargetScale
        bodyScale = newTargetScale
        isModelVisible = true

        Log.d(TAG, "Good pose → visible, scale=${"%.2f".format(newTargetScale)}")
        setBodyTransform(bodyRotationX, bodyRotationY, bodyScale, bodyPositionY)
        modelViewer.animator?.updateBoneMatrices()
    }

    fun hideModel() {
        if (!isModelReady) return
        isModelVisible = false
        setBodyTransform(bodyRotationX, bodyRotationY, 0.001f, bodyPositionY)
        modelViewer.animator?.updateBoneMatrices()
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
//    fun waitForModel(callback: () -> Unit) {
//        if (isModelReady) callback() else setOnModelReadyListener(callback)
//    }
//
//    fun hasSkeleton(): Boolean {
//        val asset = modelViewer.asset ?: return false
//        val tm = modelViewer.engine.transformManager
//        val boneKeywords = listOf("J_Bip", "Bip", "mixamorig", "Bone", "joint", "Head", "Spine", "Hip", "Shoulder")
//        val count = asset.entities.count { entity ->
//            val name = asset.getName(entity) ?: return@count false
//            tm.getInstance(entity) != 0 && boneKeywords.any { name.contains(it, ignoreCase = true) }
//        }
//        Log.d(TAG, "hasSkeleton: found $count bone-like entities")
//        return count >= 5
//    }

    private fun cleanup() {
        if (::modelViewer.isInitialized) modelViewer.destroyModel()
    }

    inner class FrameCallback : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            try {
                if (::choreographer.isInitialized) choreographer.postFrameCallback(this)
                if (::modelViewer.isInitialized && isModelReady) modelViewer.render(frameTimeNanos)
            } catch (_: Exception) { }
        }
    }
}

data class Vector3D(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f)