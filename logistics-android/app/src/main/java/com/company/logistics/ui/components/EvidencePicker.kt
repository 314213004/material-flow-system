package com.company.logistics.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 图片凭证选择器（0.5.45）：拍照或从相册选，压缩到后端限制内（JPEG ≤ 5MB），最多 [max] 张。
 *
 * 只负责"取到字节"；上传与业务提交由调用方（ViewModel）负责，离线时随队列一起保存。
 */
@Composable
fun EvidencePicker(
    evidences: List<ByteArray>,
    onChanged: (List<ByteArray>) -> Unit,
    max: Int = 3,
) {
    val context = LocalContext.current
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }

    fun append(bytes: ByteArray?) {
        if (bytes == null) return
        if (evidences.size >= max) return
        onChanged(evidences + bytes)
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        append(readDownscaled(context, uri))
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok: Boolean ->
        val file = cameraFile
        cameraFile = null
        if (ok && file != null && file.exists()) append(readDownscaled(context, Uri.fromFile(file)))
    }

    fun startCamera() {
        val dir = File(context.cacheDir, "evidence").apply { mkdirs() }
        val file = File.createTempFile("evidence-", ".jpg", dir)
        cameraFile = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        camera.launch(uri)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) startCamera()
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "凭证照片（可选）",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text("${evidences.size}/$max", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) startCamera() else permission.launch(Manifest.permission.CAMERA)
                },
                enabled = evidences.size < max,
            ) { Text("拍照", fontSize = 13.sp) }
            OutlinedButton(
                onClick = { gallery.launch("image/*") },
                enabled = evidences.size < max,
            ) { Text("相册", fontSize = 13.sp) }
        }
        if (permissionDenied) {
            Text(
                "没有相机权限，可在系统设置里开启，或改用相册选择",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (evidences.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                evidences.forEachIndexed { index, bytes ->
                    Box(Modifier.size(64.dp)) {
                        val bitmap = remember(bytes) {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "凭证 ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                        }
                        TextButton(
                            onClick = { onChanged(evidences.filterIndexed { i, _ -> i != index }) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(0.dp),
                        ) { Text("×", fontSize = 14.sp) }
                    }
                }
            }
        }
    }
}

/** 读图并压缩：最长边 ≤ 1600px、JPEG 质量 80，单张控制在几百 KB，远低于后端 5MB 上限。 */
internal fun readDownscaled(context: Context, uri: Uri, maxDimension: Int = 1600): ByteArray? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val scaled = if (maxOf(bitmap.width, bitmap.height) > maxDimension) {
            val ratio = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
        } else bitmap
        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
    } catch (_: Exception) {
        null
    }
}
