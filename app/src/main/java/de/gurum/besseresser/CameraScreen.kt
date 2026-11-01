package de.gurum.besseresser

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.view.Surface
import java.io.File

/** EXIF-Rotation auslesen und in das Pixelbild drehen; Datei wird überschrieben (JPEG q90). */
private fun correctOrientation(file: File) {
    try {
        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (degrees == 0) return
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        file.outputStream().use { out -> rotated.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        if (rotated !== bmp) bmp.recycle()
        // EXIF auf "normal" zurücksetzen (Pixelsize ist jetzt richtig dargestellt)
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            saveAttributes()
        }
    } catch (e: Exception) {
        // Im Fehlerfall bleibt die Datei unverändert (schlimmstenfalls gedreht, nicht lost)
    }
}

/**
 * Eigene Kamera (CameraX): Live-Vorschau → Verschluss → Foto + Anmerkung in EINEM Schritt
 * („OK“ = bewerten; „Wiederholen“ = zurück zur Vorschau, Text bleibt erhalten).
 * Fallback ohne Kamera-Berechtigung: Systemkamera.
 */
@Composable
fun CameraCaptureScreen(
    outputFile: File,
    note: String,
    onNoteChange: (String) -> Unit,
    onCancel: () -> Unit,
    onFallbackSystemCamera: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val providerHolder = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    var hasCaptured by remember { mutableStateOf(false) }

    // Bindet die (neu gebaute) Vorschau an den Lifecycle
    val bindCamera: () -> Unit = {
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val provider = future.get()
                    providerHolder.value = provider
                    provider.unbindAll()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture
                    )
                } catch (e: Exception) {
                    // Vorschau nicht möglich → Fallback-Angebot bleibt sichtbar
                    onFallbackSystemCamera()
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            onFallbackSystemCamera()
        }
    }

    fun capture() {
        // Kritisch: ImageCapture kennt die Display-Rotation sonst nicht → schiefes EXIF
        previewView.display?.let { imageCapture.targetRotation = it.rotation }
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(outputFile).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    // EXIF-Rotation in das Pixelbild übernehmen, damit alle Konsumenten
                    // (Review, Ergebnis, Historie, KI) das Foto aufrecht sehen
                    correctOrientation(outputFile)
                    hasCaptured = true
                }

                override fun onError(exception: ImageCaptureException) {
                    // Aufnahme fehlgeschlagen → Vorschau bleibt, Nutzer kann erneut drücken
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .imePadding()
    ) {
        when {
            !granted -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.cam_permission_text),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        fontSize = 15.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.cam_retry_permission)) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onFallbackSystemCamera,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.cam_use_system)) }
                }
            }

            !hasCaptured -> {
                // Live-Vorschau
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                LaunchedEffect(granted) { if (granted) bindCamera() }
                DisposableEffect(Unit) {
                    onDispose { providerHolder.value?.unbindAll() }
                }

                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        tint = Color.White,
                        contentDescription = stringResource(R.string.cd_back_no_save)
                    )
                }
                // Verschlussknopf: Außen-Ring mit ausgefülltem Innenkreis
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .size(80.dp)
                        .clip(CircleShape)
                        .border(5.dp, Color.White, CircleShape)
                        .clickable { capture() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }

            else -> {
                // Nach der Aufnahme: Foto nutzt den freien Platz, Textfeld direkt unterm Foto,
                // Zurückpfeil in der eigenen Kopfzeile (nicht über dem Foto)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Kopfzeile mit Zurückpfeil
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                tint = Color.White,
                                contentDescription = stringResource(R.string.cd_back_no_save)
                            )
                        }
                    }

                    // Foto: flexibler Bereich, passend skaliert (weicht der Tastatur aus)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        val bmp = remember(outputFile) {
                            try { BitmapFactory.decodeFile(outputFile.absolutePath) } catch (e: Exception) { null }
                        }
                        if (bmp != null) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = stringResource(R.string.photo_of_meal),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    // Anmerkung + Auswerten direkt untereinander
                    OutlinedTextField(
                        value = note,
                        onValueChange = onNoteChange,
                        placeholder = { Text(stringResource(R.string.main_note_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 4
                    )
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) { Text(stringResource(R.string.main_rate_now), fontSize = 17.sp) }
                }
            }
        }
    }
}
