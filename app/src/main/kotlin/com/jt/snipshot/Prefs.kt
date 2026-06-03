package com.jt.snipshot

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "snipshot_prefs")

object Prefs {
    private val KEEP_ORIGINAL = booleanPreferencesKey("keep_original")

    fun keepOriginal(context: Context): Flow<Boolean> =
        context.dataStore.data.map { it[KEEP_ORIGINAL] ?: true }

    suspend fun setKeepOriginal(context: Context, value: Boolean) {
        context.dataStore.edit { it[KEEP_ORIGINAL] = value }
    }
}
