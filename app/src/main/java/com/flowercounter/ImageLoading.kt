package com.flowercounter

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface

/** Converts a CameraX capture into an upright, working-sized ARGB bitmap. */
fun ImageProxy.toWorkingBitmap(): Bitmap {
    val rotation = imageInfo.rotationDegrees
    val full = toBitmap()
    val prepared = FlowerCounter.prepare(full, rotation)
    if (prepared !== full) full.recycle()
    return prepared
}

const val SAMPLE_ASSET = "FlowerCounting.jpeg"

/** The bundled sample photo (white parijat flowers in grass) used on the start page. */
fun loadSampleBitmap(context: Context): Bitmap {
    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
    val decoded = context.assets.open(SAMPLE_ASSET).use { BitmapFactory.decodeStream(it, null, opts) }
        ?: error("Could not decode $SAMPLE_ASSET")
    val prepared = FlowerCounter.prepare(decoded)
    if (prepared !== decoded) decoded.recycle()
    return prepared
}

/** Decodes a gallery image, honouring EXIF orientation, without loading it at full size. */
fun loadWorkingBitmap(context: Context, uri: Uri): Bitmap {
    val resolver = context.contentResolver

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= FlowerCounter.WORKING_LONG_SIDE) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
        ?: error("Could not decode image")

    val rotation = resolver.openInputStream(uri).use { stream ->
        if (stream == null) 0 else ExifInterface(stream).rotationDegrees
    }
    val prepared = FlowerCounter.prepare(decoded, rotation)
    if (prepared !== decoded) decoded.recycle()
    return prepared
}
