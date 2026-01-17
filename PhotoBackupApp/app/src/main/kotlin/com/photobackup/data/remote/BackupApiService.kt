package com.photobackup.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

data class HealthResponse(
    val status: String,
    val version: String,
    val server_name: String
)

data class FileCheckRequest(
    val hashes: List<String>
)

data class FileCheckResponse(
    val existing: List<String>,
    val missing: List<String>
)

data class UploadResponse(
    val status: String,
    val path: String?,
    val hash: String?,
    val message: String?
)

interface BackupApiService {

    @GET("/api/health")
    suspend fun healthCheck(
        @Header("X-API-Key") apiKey: String
    ): Response<HealthResponse>

    @POST("/api/files/check")
    suspend fun checkFiles(
        @Header("X-API-Key") apiKey: String,
        @Body request: FileCheckRequest
    ): Response<FileCheckResponse>

    @Multipart
    @POST("/api/files/upload")
    suspend fun uploadFile(
        @Header("X-API-Key") apiKey: String,
        @Part file: MultipartBody.Part,
        @Part("file_hash") fileHash: RequestBody,
        @Part("original_path") originalPath: RequestBody?,
        @Part("date_taken") dateTaken: RequestBody?,
        @Part("mime_type") mimeType: RequestBody?,
        @Part("device_name") deviceName: RequestBody?
    ): Response<UploadResponse>
}
