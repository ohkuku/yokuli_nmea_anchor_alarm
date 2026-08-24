package com.yokuli.anchorwatch.data.anchorage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.room.withTransaction
import com.yokuli.anchorwatch.data.database.AppDatabase
import com.yokuli.anchorwatch.data.database.entity.AnchoragePhotoEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class AnchoragePhotoRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
) {
    private val directory get() = File(context.filesDir, "anchorage_media").apply { mkdirs() }

    suspend fun import(placeId: Long, source: Uri, caption: String = ""): AnchoragePhotoEntity = withContext(Dispatchers.IO) {
        require(database.anchoragePlaceDao().get(placeId) != null) { "Place no longer exists" }
        require(database.anchoragePhotoDao().countForPlace(placeId) < MAX_PER_PLACE) { "A Place can contain at most $MAX_PER_PLACE photos." }
        val mime = context.contentResolver.getType(source).orEmpty().lowercase()
        require(mime in ALLOWED_MIME) { "Choose a JPEG, PNG or WebP image." }
        val length = context.contentResolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1L
        require(length < 0 || length <= MAX_INPUT_BYTES) { "The selected image is larger than 25 MB." }
        val decoded = decode(source) ?: error("The selected image could not be decoded.")
        val uuid = UUID.randomUUID().toString()
        val fullName = "$uuid.jpg"; val thumbName = "thumb_$uuid.jpg"
        val full = File(directory, fullName); val thumb = File(directory, thumbName)
        try {
            val stored = decoded.scaled(MAX_SIDE)
            full.outputStream().use { check(stored.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            val thumbnail = stored.scaled(THUMB_SIDE)
            thumb.outputStream().use { check(thumbnail.compress(Bitmap.CompressFormat.JPEG, 82, it)) }
            val entity = AnchoragePhotoEntity(
                placeId = placeId, relativeFileName = fullName, thumbnailRelativeFileName = thumbName,
                mimeType = "image/jpeg", sha256 = sha256(full), width = stored.width, height = stored.height,
                caption = caption.take(500), createdAt = System.currentTimeMillis(),
            )
            val id = database.anchoragePhotoDao().insert(entity)
            if (thumbnail !== stored) thumbnail.recycle(); if (stored !== decoded) stored.recycle(); decoded.recycle()
            entity.copy(id = id)
        } catch (error: Throwable) {
            full.delete(); thumb.delete(); if (!decoded.isRecycled) decoded.recycle(); throw error
        }
    }

    suspend fun delete(value: AnchoragePhotoEntity) = withContext(Dispatchers.IO) {
        database.withTransaction { database.anchoragePhotoDao().delete(value) }
        File(directory, File(value.relativeFileName).name).delete()
        value.thumbnailRelativeFileName?.let { File(directory, File(it).name).delete() }
    }

    suspend fun cleanupOrphans(): Int = withContext(Dispatchers.IO) {
        val used = database.anchoragePhotoDao().allNow().flatMapTo(mutableSetOf()) { listOfNotNull(File(it.relativeFileName).name, it.thumbnailRelativeFileName?.let(::File)?.name) }
        directory.listFiles().orEmpty().count { it.isFile && it.name !in used && it.delete() }
    }

    fun file(value: AnchoragePhotoEntity, thumbnail: Boolean = false): File =
        File(directory, File(if (thumbnail) value.thumbnailRelativeFileName ?: value.relativeFileName else value.relativeFileName).name)

    private fun decode(uri: Uri): Bitmap? = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
    } else context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)

    private fun Bitmap.scaled(maxSide: Int): Bitmap {
        val largest = maxOf(width, height); if (largest <= maxSide) return this
        val ratio = maxSide.toDouble() / largest
        return Bitmap.createScaledBitmap(this, (width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1), true)
    }
    private fun sha256(file: File): String { val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val buffer=ByteArray(64*1024);while(true){val read=input.read(buffer);if(read<0)break;digest.update(buffer,0,read)}};return digest.digest().joinToString(""){"%02x".format(it)} }

    companion object { const val MAX_PER_PLACE=20;const val MAX_INPUT_BYTES=25L*1024*1024;const val MAX_SIDE=4096;const val THUMB_SIDE=512;private val ALLOWED_MIME=setOf("image/jpeg","image/png","image/webp") }
}
