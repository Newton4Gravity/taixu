package top.wkbin.taixu.core.network

import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Retrofit API service interface for backend communication.
 */
interface ApiService {
    @GET("health")
    suspend fun healthCheck(): ApiResponse<HealthResponse>

    @GET("bootstrap")
    suspend fun bootstrap(): ApiResponse<BootstrapResponse>

    @POST("session/bootstrap")
    suspend fun sessionBootstrap(@Body body: SessionBootstrapRequest): ApiResponse<Unit>

    @GET("conversations")
    suspend fun listConversations(): ApiResponse<List<ConversationResponse>>

    @POST("conversations")
    suspend fun createConversation(@Body body: CreateConversationRequest): ApiResponse<ConversationResponse>

    @GET("conversations/{id}")
    suspend fun getConversation(@Path("id") id: String): ApiResponse<ConversationResponse>

    @DELETE("conversations/{id}")
    suspend fun deleteConversation(@Path("id") id: String): ApiResponse<Unit>

    @GET("conversations/{id}/messages")
    suspend fun getMessages(
        @Path("id") id: String,
        @Query("mode") mode: String? = null
    ): ApiResponse<List<MessageResponse>>

    @POST("conversations/{id}/runs")
    suspend fun runConversation(
        @Path("id") id: String,
        @Body body: RunRequest
    ): ApiResponse<RunResponse>

    @GET("conversations/{id}/approvals")
    suspend fun getApprovals(@Path("id") id: String): ApiResponse<List<ApprovalResponse>>

    @POST("conversations/{id}/approvals/{approvalId}")
    suspend fun resolveApproval(
        @Path("id") id: String,
        @Path("approvalId") approvalId: String,
        @Body body: ApprovalResolutionRequest
    ): ApiResponse<ApprovalResolutionResponse>

    @POST("tasks/{taskId}/cancel")
    suspend fun cancelTask(@Path("taskId") taskId: String): ApiResponse<Unit>

    @GET("workspaces")
    suspend fun listWorkspaces(@Query("path") path: String? = null): ApiResponse<WorkspaceListingResponse>

    @GET("workspaces/file")
    suspend fun getWorkspaceFile(
        @Query("path") path: String,
        @Query("maxChars") maxChars: Int? = null
    ): ApiResponse<WorkspaceFileResponse>

    @PUT("workspaces/file")
    suspend fun saveWorkspaceFile(@Body body: SaveWorkspaceFileRequest): ApiResponse<Unit>

    @GET
    suspend fun downloadFile(@Url url: String): okhttp3.Response
}

data class ApiResponse<T>(
    val data: T? = null,
    val error: String? = null,
    val success: Boolean = true
)

data class HealthResponse(
    val status: String,
    val version: String,
    val timestamp: Long
)

data class SessionBootstrapRequest(
    val token: String
)

data class BootstrapResponse(
    val workspace: WorkspaceInfoResponse?,
    val quickPhrases: List<QuickPhraseResponse>
)

data class WorkspaceInfoResponse(
    val workspace: WorkspaceResponse?,
    val root: RootPathResponse?
)

data class WorkspaceResponse(
    val id: String,
    val name: String,
    val rootPath: String
)

data class RootPathResponse(
    val path: String
)

data class QuickPhraseResponse(
    val id: String,
    val label: String,
    val prompt: String,
    val category: String
)

data class ConversationResponse(
    val id: String,
    val title: String?,
    val mode: String,
    val workspace: String?,
    val createdAt: Long,
    val updatedAt: Long
)

data class CreateConversationRequest(
    val title: String?,
    val mode: String = "normal",
    val workspace: String? = null
)

data class MessageResponse(
    val id: String,
    val type: Int,
    val user: Int,
    val content: MessageContent,
    val createAt: Long
)

data class MessageContent(
    val id: String,
    val text: String?,
    val attachments: List<AttachmentResponse>? = null
)

data class AttachmentResponse(
    val id: String,
    val type: String,
    val url: String?,
    val name: String?,
    val size: Long?
)

data class RunRequest(
    val taskId: String,
    val userMessage: String,
    val userMessageCreatedAt: Long,
    val conversationMode: String,
    val attachments: List<AttachmentResponse> = emptyList()
)

data class RunResponse(
    val taskId: String,
    val turnId: String?,
    val conversation: ConversationResponse?,
    val conversationMode: String?
)

data class ApprovalResponse(
    val id: String,
    val type: String,
    val message: String,
    val command: String?,
    val riskLevel: String
)

data class ApprovalResolutionRequest(
    val approved: Boolean
)

data class ApprovalResolutionResponse(
    val taskId: String?
)

data class WorkspaceListingResponse(
    val path: String,
    val items: List<WorkspaceItemResponse>
)

data class WorkspaceItemResponse(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long?,
    val modifiedAt: Long?
)

data class WorkspaceFileResponse(
    val content: String?,
    val path: String,
    val size: Long
)

data class SaveWorkspaceFileRequest(
    val path: String,
    val content: String,
    val append: Boolean = false
)