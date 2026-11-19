package com.cursorforandroid.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Cursor's Environments API (public beta since Oct 2026): environments, their builds, and their secrets.
 * Kept apart from [CursorApi] so fakes of that interface need not grow. Every field is nullable or defaulted,
 * as in the agent DTOs, so one odd row cannot fail a page.
 */
interface EnvironmentsApi {

    @GET("v1/environments")
    suspend fun list(
        @Query("query") query: String? = null,
        @Query("owner") owner: String? = null,
        @Query("limit") limit: Int = 20,
        @Query("cursor") cursor: String? = null,
    ): EnvironmentPageDto

    @GET("v1/environments/{id}")
    suspend fun get(@Path("id") id: String): EnvironmentDto

    @GET("v1/environments/{id}/builds")
    suspend fun builds(
        @Path("id") id: String,
        @Query("status") status: String? = null,
        @Query("limit") limit: Int = 10,
        @Query("cursor") cursor: String? = null,
    ): EnvironmentBuildPageDto

    @GET("v1/environments/{id}/builds/{buildId}")
    suspend fun build(@Path("id") id: String, @Path("buildId") buildId: String): EnvironmentBuildDto

    @GET("v1/environments/{id}/builds/active")
    suspend fun activeBuild(@Path("id") id: String): ActiveBuildDto

    /** Starts a build (202). A non-default branch in [StartBuildRequestDto.refs] needs `draft = true`. */
    @POST("v1/environments/{id}/builds")
    suspend fun startBuild(@Path("id") id: String, @Body body: StartBuildRequestDto = StartBuildRequestDto()): StartedBuildDto

    @GET("v1/environments/{id}/secrets")
    suspend fun secrets(@Path("id") id: String, @Query("cursor") cursor: String? = null): EnvironmentSecretPageDto

    /** Creates (201) or changes (200) a secret. Values are never read back. */
    @PUT("v1/environments/{id}/secrets/{name}")
    suspend fun putSecret(
        @Path("id") id: String,
        @Path("name") name: String,
        @Body body: PutSecretRequestDto,
        @Query("id") versionId: String? = null,
    ): EnvironmentSecretDto

    /** Irreversible. [name] must match exactly, case included. */
    @DELETE("v1/environments/{id}/secrets/{name}")
    suspend fun deleteSecret(
        @Path("id") id: String,
        @Path("name") name: String,
        @Query("id") versionId: String? = null,
    ): JsonObject

    companion object {
        fun retrofit(client: OkHttpClient, baseUrl: String = CursorEndpoints.BASE_URL): EnvironmentsApi = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(CursorJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(EnvironmentsApi::class.java)

        /** Letters, digits and underscores, not starting with a digit, at most 255, and not a reserved proxy name. */
        fun isValidSecretName(name: String): Boolean =
            name.length in 1..255 && SECRET_NAME.matches(name) && name.uppercase() !in RESERVED_SECRET_NAMES

        private val SECRET_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        private val RESERVED_SECRET_NAMES = setOf("CURSOR_SANDBOX", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")
    }
}

@Serializable data class EnvironmentRepoDto(val url: String = "")

@Serializable
data class BuildSettingsDto(
    /** `when_stale` (default), `never`, or legacy read-only `always`. */
    val pullLatestCode: String? = null,
    /** Null means 24. */
    val stalenessThresholdHours: Int? = null,
    /** Null means 90. */
    val installTimeoutMinutes: Int? = null,
)

@Serializable
data class EnvironmentDto(
    val id: String = "",
    val name: String? = null,
    /** `personal` or `team`. */
    val owner: String? = null,
    val repos: List<EnvironmentRepoDto> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val environmentJson: String? = null,
    val versionId: String? = null,
    val buildSettings: BuildSettingsDto? = null,
)

/** A page of environments. Pages may be short or empty; keep going while [nextCursor] is present. */
@Serializable data class EnvironmentPageDto(val items: List<EnvironmentDto> = emptyList(), val nextCursor: String? = null)

@Serializable data class BuildRefDto(val repoUrl: String = "", val ref: String = "")

@Serializable data class BuildCommitDto(val repoUrl: String = "", val commitSha: String = "")

@Serializable data class BuildFailureDto(val type: String? = null, val code: String? = null)

@Serializable
data class EnvironmentBuildDto(
    val id: String = "",
    val environmentId: String? = null,
    /** IN_PROGRESS, SUCCEEDED, FAILED, CANCELLED or SKIPPED. */
    val status: String? = null,
    /** RECURRING, CONFIG_CHANGE or MANUAL. */
    val trigger: String? = null,
    /** SYSTEM, WEBSITE, API or AGENT. */
    val source: String? = null,
    val draft: Boolean = false,
    val failure: BuildFailureDto? = null,
    val requestedRefs: List<BuildRefDto> = emptyList(),
    val repoCommits: List<BuildCommitDto> = emptyList(),
    val snapshotState: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** Absent while the build runs. */
    val completedAt: String? = null,
) {
    val inProgress: Boolean get() = status == "IN_PROGRESS"
}

@Serializable data class EnvironmentBuildPageDto(val items: List<EnvironmentBuildDto> = emptyList(), val nextCursor: String? = null)

/** [type] is `build` (with [buildId]) or `universal_image`. */
@Serializable data class ActiveBuildDto(val type: String? = null, val buildId: String? = null)

@Serializable data class StartBuildRequestDto(val draft: Boolean? = null, val refs: List<BuildRefDto>? = null)

/** Reading the new build right after this can briefly 404. */
@Serializable data class StartedBuildDto(val id: String = "", val environmentId: String? = null)

@Serializable
data class EnvironmentSecretDto(
    val id: String? = null,
    val name: String = "",
    /** runtime_secret, environment_variable or build_secret. */
    val type: String? = null,
    /** Empty means every repo. */
    val repos: List<String> = emptyList(),
    val createdAt: String? = null,
)

@Serializable data class EnvironmentSecretPageDto(val items: List<EnvironmentSecretDto> = emptyList(), val nextCursor: String? = null)

/** [value] is 1 to 4096 bytes. [type] and [repos] left null keep what an existing secret has. */
@Serializable data class PutSecretRequestDto(val value: String, val type: String? = null, val repos: List<String>? = null)
