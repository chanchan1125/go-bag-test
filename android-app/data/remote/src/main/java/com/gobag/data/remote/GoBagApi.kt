package com.gobag.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface GoBagApi {
    @GET("/health")
    suspend fun health(): HealthResponseDto

    @GET("/device/status")
    suspend fun device_status(): DeviceStatusDto

    @GET("/sync/status")
    suspend fun sync_status(): SyncStatusDto

    @GET("/time")
    suspend fun time(): Map<String, Long>

    @GET("/templates")
    suspend fun templates(): TemplatesResponseDto

    @POST("/pair")
    suspend fun pair(@Body request: PairRequestDto): PairResponseDto

    @POST("/sync")
    suspend fun sync(@Body request: SyncRequestDto): SyncResponseDto
}
