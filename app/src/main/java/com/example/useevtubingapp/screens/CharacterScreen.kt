package com.example.useevtubingapp.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.useevtubingapp.MainActivity
import com.example.useevtubingapp.ui.theme.blueDark7
import com.example.useevtubingapp.ModelRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream


sealed class UIEvent {
    data class SingleImageChanged(val uri: Uri, val context: Context) : UIEvent()
}

data class UIState(
    val isUploading: Boolean = false,
    val image: String? = null
)


class UIViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(UIState())

    val uiState: StateFlow<UIState> = _uiState.asStateFlow()


    fun onEvent(event: UIEvent, activity: MainActivity) {
        when (event) {
            is UIEvent.SingleImageChanged -> {
                uploadSingleImage(event.context, event.uri, activity)
            }
        }
    }

    private fun uploadSingleImage(context: Context, uri: Uri, activity: MainActivity) {
        _uiState.update { it.copy(isUploading = true) }
        val file = createFileFromContentUri(context, uri)
        if (checkToVRM(file)) {
            val file_glb_path = activity.createFilePath(file.absolutePath)
            val file_glb = activity.convertVRMtoGLBcpp(file.absolutePath, file_glb_path)
            if (file_glb != file.absolutePath) {
                activity.loadAvatar(file_glb)
                _uiState.update { it.copy(image = file_glb) }
            } else {
                Toast.makeText(context, "Ошибка загрузки файла", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "Загружен файл не VRM формата", Toast.LENGTH_LONG).show()
        }
        _uiState.update { it.copy(isUploading = false) }
    }

    fun deleteAvatarData(): Boolean {
        try {
            _uiState.update { it.copy(image = null) }
            return true
        } catch (e: Exception) {
            Log.e("UIViewModel", "Error deleting", e)
            return false
        }
    }

    fun updateProgress(progress: Boolean) {
        _uiState.update { it.copy(isUploading = progress) }
    }

    fun updateImage(image: String){
        _uiState.update { it.copy(image = image) }
    }
}


fun checkToVRM(file: File): Boolean{
    val ext: String = file.extension
    if (ext == "vrm"){
        return true
    }
    return false
}


fun createFileFromContentUri(context: Context, fileUri: Uri): File {
    var fileName = ""

    // Получаем имя файла
    context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst()) {
            fileName = cursor.getString(nameIndex)
        }
    }

    // Если не удалось получить имя, создаем уникальное
    if (fileName.isBlank()) {
        fileName = "file_${System.currentTimeMillis()}"
    }


    val inputStream: InputStream = context.contentResolver.openInputStream(fileUri)
        ?: throw IOException("Не удалось открыть InputStream для URI: $fileUri")


    val outputDir = context.cacheDir
    val outputFile = File(outputDir, fileName)


    copyStreamToFile(inputStream, outputFile)
    inputStream.close()

    return outputFile
}

fun copyStreamToFile(inputStream: InputStream, outputFile: File) {
    inputStream.use { input ->
        val outputStream = FileOutputStream(outputFile)
        outputStream.use { output ->
            val buffer = ByteArray(4 * 1024) // buffer size
            while (true) {
                val byteCount = input.read(buffer)
                if (byteCount < 0) break
                output.write(buffer, 0, byteCount)
            }
            output.flush()
        }
    }
}

fun deleteAvatar(avatar: String, activity: MainActivity) : Boolean{
    try {
        activity.deleteFile(File(avatar))
        return true
    } catch (e: Exception){
        return false
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterScreen(mainActivity: MainActivity) {
    val expanded = remember { mutableStateOf(false) }

    val context = LocalContext.current
    val viewModel: UIViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()

    mainActivity.getAvatar()?.let{
        viewModel.updateImage(it)
    }

    val singleImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let { selectedUri ->
                val event = UIEvent.SingleImageChanged(selectedUri, context)
                viewModel.onEvent(event, mainActivity)
            }
        }
    )

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical=30.dp).absoluteOffset(x=0.dp, y=0.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical=30.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Top
            ){
                if (uiState.image != null){
                    val file = File(uiState.image)
                    val renderer = ModelRenderer()
                    Surface(
                        modifier = Modifier.width(400.dp).height(400.dp),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AndroidView(factory = { context ->
                            SurfaceView(context).apply {
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                renderer.onSurfaceAvailable(this, mainActivity.lifecycle, file)
                                renderer.setOnModelReadyListener {
                                    renderer.setInitialTransform(0.125f, 180f)
                                    renderer.normalizeBodyTransform()
                                }
                                setTag(renderer)
                            }
                        })
                    }
                } else {
                    Icon(
                        Icons.Filled.AccountCircle,
                        contentDescription = "person avatar icon",
                        tint=blueDark7,
                        modifier = Modifier.size(200.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(5.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Top
            ){
                uiState.image?.let{
                    Text(
                        modifier= Modifier.size(200.dp, 50.dp)
                            .padding(10.dp),
                        text="Модель загружена"
                    )
                } ?: run {
                    Text(
                        modifier= Modifier.size(200.dp, 50.dp)
                            .padding(10.dp),
                        text="Загрузите модель .vrm"
                    )
                }

                OutlinedButton(
                    onClick = {
                        if (!uiState.isUploading) {
                            singleImagePickerLauncher.launch("*/*")
                        } else {
                            Toast.makeText(context, "Идет загрузка, ожидайте", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier= Modifier.size(50.dp),
                    contentPadding = PaddingValues(0.dp),
                    border = BorderStroke(0.dp, color=Color(0x00FFFFFF)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor =  blueDark7)
                ) {
                    Icon(
                        Icons.Filled.Save,
                        contentDescription = "load new avatar",
                        tint=blueDark7,
                        modifier = Modifier.size(50.dp)
                    )
                }

                OutlinedButton(
                    onClick = {
                        val file = uiState.image
                        viewModel.deleteAvatarData() // очищает uiState.image
                        file?.let {
                            if (deleteAvatar(file, mainActivity)){
                                Toast.makeText(context, "Файл модели успешно удалён", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Файл модели не удалён из-за ошибки", Toast.LENGTH_LONG).show()
                            }
                        } ?: run {
                            Toast.makeText(context, "Файл модели не загружен", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier= Modifier.size(50.dp),
                    contentPadding = PaddingValues(0.dp),
                    border = BorderStroke(0.dp, color=Color(0x00FFFFFF)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor =  blueDark7)
                ) {
                    Icon(
                        Icons.Filled.DeleteForever ,
                        contentDescription = "delete avatar",
                        tint=blueDark7,
                        modifier = Modifier.size(50.dp)
                    )
                }
            }
        }
    }
}