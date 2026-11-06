package de.gurum.besseresser

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
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

                // Kopfzeile über dem Kamerabild mit abgedunkeltem Kreis-Button & Hinweis-Chip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.50f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                tint = Color.White,
                                contentDescription = stringResource(R.string.cd_back_no_save)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.Black.copy(alpha = 0.50f)
                    ) {
                        Text(
                            stringResource(R.string.cam_hint_frame),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }

                    Spacer(Modifier.size(44.dp))
                }

                // Verschlussknopf: Außen-Ring mit ausgefülltem Innenkreis
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 36.dp)
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.25f))
                        .border(4.dp, Color.White, CircleShape)
                        .clickable { capture() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }

            else -> {
                // Nach der Aufnahme: Theme-gerechter Hintergrund (damit das Textfeld im
                // Light- & Dark-Mode perfekt lesbar ist), Foto-Vorschau, Notiz & Bewerten/Wiederholen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TopBarRow(
                        title = stringResource(R.string.review_title),
                        onBack = onCancel,
                        trailing = { }
                    )

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
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = stringResource(R.string.photo_of_meal),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    // Anmerkung + Auswerten + Wiederholen direkt untereinander
                    OutlinedTextField(
                        value = note,
                        onValueChange = onNoteChange,
                        placeholder = { Text(stringResource(R.string.main_note_placeholder)) },
                        trailingIcon = {
                            // Letzte 5 Eingaben: Auswahl rückt nach oben (MRU)
                            RecentNotesButton(onPick = onNoteChange)
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 4
                    )
                    Button(
                        onClick = onConfirm,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(
                            stringResource(R.string.main_rate_now),
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    OutlinedButton(
                        onClick = { hasCaptured = false },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(stringResource(R.string.main_retake))
                    }
                }
            }
        }
    }
}
