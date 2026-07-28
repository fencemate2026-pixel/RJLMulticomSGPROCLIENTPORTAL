package com.example.rjlmulticomsg_proclientportal.data.session

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sessionDataStore by preferencesDataStore(name = "client_portal_session")

class SessionStore(private val context: Context) {
    private val userIdKey = stringPreferencesKey("user_id")
    private val activeAccountIdKey = stringPreferencesKey("active_account_id")
    private val rememberEmailKey = stringPreferencesKey("remember_email")
    private val rememberMeKey = booleanPreferencesKey("remember_me")

    val userId: Flow<String?> = context.sessionDataStore.data.map { it[userIdKey] }
    val activeAccountId: Flow<String?> = context.sessionDataStore.data.map { it[activeAccountIdKey] }
    val rememberEmail: Flow<String?> = context.sessionDataStore.data.map { it[rememberEmailKey] }
    val rememberMe: Flow<Boolean> = context.sessionDataStore.data.map { it[rememberMeKey] ?: false }

    suspend fun setLoggedIn(userId: String, email: String, remember: Boolean) {
        context.sessionDataStore.edit { prefs ->
            prefs[userIdKey] = userId
            if (remember) {
                prefs[rememberEmailKey] = email
                prefs[rememberMeKey] = true
            } else {
                prefs.remove(rememberEmailKey)
                prefs[rememberMeKey] = false
            }
        }
    }

    suspend fun setActiveAccountId(accountId: String) {
        context.sessionDataStore.edit { prefs ->
            prefs[activeAccountIdKey] = accountId
        }
    }

    suspend fun clearSession() {
        context.sessionDataStore.edit { prefs ->
            prefs.remove(userIdKey)
            prefs.remove(activeAccountIdKey)
            // keep remember email if remember me was on
            if (prefs[rememberMeKey] != true) {
                prefs.remove(rememberEmailKey)
            }
        }
    }
}
