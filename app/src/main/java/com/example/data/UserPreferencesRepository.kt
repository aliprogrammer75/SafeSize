package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class UserPreferencesRepository(private val context: Context) {
    private val INITIAL_BALANCE = doublePreferencesKey("initial_balance")

    val initialBalance: Flow<Double> = context.dataStore.data
        .map { preferences ->
            // Default to $10,000 if not set
            preferences[INITIAL_BALANCE] ?: 10000.0
        }

    suspend fun updateInitialBalance(balance: Double) {
        context.dataStore.edit { preferences ->
            preferences[INITIAL_BALANCE] = balance
        }
    }
}
