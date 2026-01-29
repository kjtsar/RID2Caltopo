package org.ncssar.rid2caltopo.video

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.ncssar.rid2caltopo.data.CaltopoClient

class SubmitClueWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val submission = inputData.toClueSubmission()
            // CaltopoClient.SubmitClue(submission)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
