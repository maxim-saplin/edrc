package com.example.battery_stats

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

class CollectorHeartbeatJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        CollectorStarter.startIfReady(this)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        const val JOB_ID = 7101
        private const val PERIOD_MS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.allPendingJobs.any { it.id == JOB_ID }) return
            val info = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, CollectorHeartbeatJob::class.java),
            )
                .setPersisted(true)
                .setPeriodic(PERIOD_MS)
                .setRequiresCharging(false)
                .build()
            scheduler.schedule(info)
        }
    }
}
