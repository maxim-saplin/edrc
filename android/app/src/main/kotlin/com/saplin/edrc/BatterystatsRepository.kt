package com.saplin.edrc

import android.content.Context

object BatterystatsRepository {
    fun metrics(context: Context, force: Boolean = false): Map<String, Any?> {
        return ShizukuHelper.collect(context, force)
    }
}
