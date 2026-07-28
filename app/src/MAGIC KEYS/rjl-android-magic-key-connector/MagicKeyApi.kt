package com.example.rjlmulticomsg_proclientportal.data.remote

import com.example.rjlmulticomsg_proclientportal.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

@JsonClass(generateAdapter = true)
data class VerifyMagicKeyRequest(
    val key: String,
    @Json(name = "tenant_id")
    val tenantId: String = "magic_keys_settlement_road"
)

@JsonClass(generateAdapter = true)
data class VerifyMagicKeyResponse(
    val authorized: Boolean = false,
    @Json(name = "tenant_id")
    val tenantId: String? = null,
    val site: String? = null,
    @Json(name = "expires_at")
    val expiresAt: String? = null,
    val error: String? = null,
    @Json(name = "retry_after_seconds")
    val retryAfterSeconds: Int? = null
)

interface MagicKeyApi {
    @POST("verify-magic-key")
    suspend fun verify(@Body request: VerifyMagicKeyRequest): Response<VerifyMagicKeyResponse>
}

object MagicKeyNetwork {
    val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    val api: MagicKeyApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.MAGIC_KEY_BASE_URL)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(MagicKeyApi::class.java)
    }
}
