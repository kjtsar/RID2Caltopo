package org.ncssar.rid2caltopo.video

import android.content.Context
import android.graphics.Bitmap
import java.io.File

object SnapshotStore {

    fun save(context: Context, bitmap: Bitmap): String {
        val dir = File(context.filesDir, "clues")
        dir.mkdirs()

        val file = File(dir, "clue_${System.currentTimeMillis()}.jpg")

        file.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }

        return file.absolutePath
    }
}
