package com.gobag.data.remote

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RemoteDataSourceFactory {
    fun create_api(base_url: String, auth_token: String = ""): GoBagApi {
        val gson = GsonBuilder()
            // FastAPI validates null-vs-missing fields differently, so sync payloads need explicit nulls.
            .serializeNulls()
            .create()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = if (auth_token.isBlank()) {
                    chain.request()
                } else {
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $auth_token")
                        .build()
                }
                chain.proceed(req)
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(if (base_url.endsWith("/")) base_url else "$base_url/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(GoBagApi::class.java)
    }
}
