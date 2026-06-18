package com.example.smarttourism.core.network

import com.example.smarttourism.BuildConfig
import com.example.smarttourism.data.remote.api.PoiApi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiModule {
    fun createPoiApi(deviceIdProvider: () -> String): PoiApi {
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val deviceId = deviceIdProvider().trim()
                val requestBuilder = chain.request().newBuilder()
                if (deviceId.isNotEmpty()) {
                    requestBuilder.header("X-Device-Id", deviceId)
                }
                chain.proceed(requestBuilder.build())
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PoiApi::class.java)
    }
}
