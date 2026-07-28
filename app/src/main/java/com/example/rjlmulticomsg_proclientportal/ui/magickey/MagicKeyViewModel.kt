package com.example.rjlmulticomsg_proclientportal.ui.magickey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.rjlmulticomsg_proclientportal.data.remote.MagicKeyApi
import com.example.rjlmulticomsg_proclientportal.data.remote.MagicKeyNetwork
import com.example.rjlmulticomsg_proclientportal.data.remote.VerifyMagicKeyRequest
import com.example.rjlmulticomsg_proclientportal.data.remote.VerifyMagicKeyResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface MagicKeyUiState {
    data object Idle : MagicKeyUiState
    data object Loading : MagicKeyUiState
    data class Granted(val result: VerifyMagicKeyResponse) : MagicKeyUiState
    data class Error(val message: String) : MagicKeyUiState
}

class MagicKeyViewModel(
    private val api: MagicKeyApi = MagicKeyNetwork.api
) : ViewModel() {
    private val _state = MutableStateFlow<MagicKeyUiState>(MagicKeyUiState.Idle)
    val state: StateFlow<MagicKeyUiState> = _state.asStateFlow()

    fun verify(rawKey: String) {
        if (_state.value == MagicKeyUiState.Loading) return

        val key = rawKey.filter(Char::isDigit)
        if (key.length != 6) {
            _state.value = MagicKeyUiState.Error("Enter the six-digit Magic Key.")
            return
        }

        _state.value = MagicKeyUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val response = api.verify(VerifyMagicKeyRequest(key))
                val body = if (response.isSuccessful) {
                    response.body()
                } else {
                    response.errorBody()?.use {
                        MagicKeyNetwork.moshi.adapter(VerifyMagicKeyResponse::class.java)
                            .fromJson(it.string())
                    }
                }

                if (response.isSuccessful && body?.authorized == true) {
                    MagicKeyUiState.Granted(body)
                } else {
                    MagicKeyUiState.Error(
                        magicKeyErrorMessage(body?.error, response.code())
                    )
                }
            } catch (_: Exception) {
                MagicKeyUiState.Error(
                    "Network error. Check your internet connection and try again."
                )
            }
        }
    }
}

fun magicKeyErrorMessage(error: String?, httpCode: Int): String = when {
    httpCode == 429 || error == "too_many_attempts" ->
        "Too many incorrect attempts. Wait 15 minutes and try again."
    error == "key_must_be_six_digits" ->
        "Enter the six-digit Magic Key."
    error == "invalid_or_expired_key" || httpCode == 401 ->
        "That Magic Key is incorrect or has expired."
    error == "account_inactive" || httpCode == 403 ->
        "This Magic Keys account is inactive. Contact RJL Commercial."
    error == "verification_unavailable" || httpCode >= 500 ->
        "The secure verification service is temporarily unavailable."
    else ->
        "Access could not be verified. Please try again."
}
