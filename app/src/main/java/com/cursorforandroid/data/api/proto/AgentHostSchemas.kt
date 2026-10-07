package com.cursorforandroid.data.api.proto

import com.cursorforandroid.data.api.proto.ProtoWire.Field
import com.cursorforandroid.data.api.proto.ProtoWire.Kind
import com.cursorforandroid.data.api.proto.ProtoWire.Schema

/**
 * The `agent.v1` messages wrapped as protobuf `data` on `aiserver.v1.CallAgentHost` and `StreamAgentHost`, from
 * Cursor 3.23.23's `workbench.glass.main.js` descriptors. Connect JSON carries the outer request; the inner payload
 * is binary protobuf, so these schemas are for [ProtoWire] encode/decode of that `data` field.
 */
object AgentHostSchemas {

    private fun msg(name: String, vararg fields: Field) = Schema(name, fields.toList())
    private fun str(no: Int, name: String, repeated: Boolean = false) = Field(no, name, Kind.STRING, repeated)
    private fun bytes(no: Int, name: String, repeated: Boolean = false) = Field(no, name, Kind.BYTES, repeated)
    private fun bool(no: Int, name: String) = Field(no, name, Kind.BOOL)
    private fun u64(no: Int, name: String) = Field(no, name, Kind.UINT64)
    private fun i32(no: Int, name: String) = Field(no, name, Kind.INT32)
    private fun enum(no: Int, name: String) = Field(no, name, Kind.ENUM)
    private fun sub(no: Int, name: String, repeated: Boolean = false, schema: () -> Schema) =
        Field(no, name, Kind.MESSAGE, repeated, schema)

    val WORKSPACE: Schema = msg(
        "agent.v1.AgentHostWorkspace",
        str(1, "workspacePaths", repeated = true),
        str(2, "worktreeMainPath"),
        str(3, "displayPath"),
    )

    val SESSION_OPTIONS: Schema = msg(
        "agent.v1.AgentHostSessionOptions",
        str(1, "workspacePaths", repeated = true),
        str(2, "worktreeMainPath"),
        bool(3, "isGlassRoot"),
    )

    val SESSION: Schema = msg(
        "agent.v1.AgentHostSession",
        str(1, "sessionId"),
        enum(2, "status"),
        u64(3, "lastEventId"),
        str(4, "forkedFromSessionId"),
        str(6, "title"),
        str(8, "modelId"),
        str(11, "runningTurnId"),
        str(12, "queuedTurnIds", repeated = true),
        sub(13, "workspace") { WORKSPACE },
    )

    val LIST_SESSIONS_REQUEST: Schema = msg(
        "agent.v1.ListAgentHostSessionsRequest",
        bool(1, "includeChildren"),
        str(2, "parentSessionId"),
        i32(3, "limit"),
        str(4, "workspacePathsFilter", repeated = true),
        bool(5, "matchAnyWorkspaceRoot"),
        str(6, "ownerClientInstanceId"),
        bool(7, "includeOwnerUnrootedSessions"),
    )

    val LIST_SESSIONS_RESPONSE: Schema = msg(
        "agent.v1.ListAgentHostSessionsResponse",
        sub(1, "sessions", repeated = true) { SESSION },
    )

    val SEND_MESSAGE_REQUEST: Schema = msg(
        "agent.v1.SendAgentHostMessageRequest",
        str(1, "sessionId"),
        str(2, "text"),
        str(3, "modelId"),
        str(7, "messageId"),
    )

    val SEND_MESSAGE_RESPONSE: Schema = msg(
        "agent.v1.SendAgentHostMessageResponse",
        str(1, "turnId"),
    )

    val CREATE_SESSION_REQUEST: Schema = msg(
        "agent.v1.CreateAgentHostSessionRequest",
        str(1, "sessionId"),
        sub(3, "sessionOptions") { SESSION_OPTIONS },
        str(4, "clientInstanceId"),
    )

    val CREATE_SESSION_RESPONSE: Schema = msg("agent.v1.CreateAgentHostSessionResponse")

    val LIST_WORKSPACES_REQUEST: Schema = msg("agent.v1.ListAgentHostWorkspacesRequest")

    val LIST_WORKSPACES_RESPONSE: Schema = msg(
        "agent.v1.ListAgentHostWorkspacesResponse",
        sub(1, "workspaces", repeated = true) { WORKSPACE },
    )

    val ATTACH_REQUEST: Schema = msg(
        "agent.v1.AttachAgentHostSessionRequest",
        str(1, "sessionId"),
        u64(2, "lastEventId"),
        bool(3, "backgroundTaskCompletionsOnly"),
        str(4, "clientInstanceId"),
    )

    val TEXT_DELTA: Schema = msg(
        "agent.v1.TextDeltaUpdate",
        str(1, "text"),
        bool(2, "isServerNotice"),
    )

    val THINKING_DELTA: Schema = msg(
        "agent.v1.ThinkingDeltaUpdate",
        str(1, "text"),
    )

    val USER_MESSAGE_APPENDED: Schema = msg(
        "agent.v1.UserMessageAppendedUpdate",
        sub(1, "userMessage") { AgentSchemas.USER_MESSAGE },
    )

    val INTERACTION_UPDATE: Schema = msg(
        "agent.v1.InteractionUpdate",
        sub(1, "textDelta") { TEXT_DELTA },
        sub(4, "thinkingDelta") { THINKING_DELTA },
        sub(6, "userMessageAppended") { USER_MESSAGE_APPENDED },
    )

    val TURN_STARTED: Schema = msg(
        "agent.v1.AgentHostTurnStarted",
        str(1, "turnId"),
    )

    val TURN_AWAITING_INPUT: Schema = msg(
        "agent.v1.AgentHostTurnAwaitingInput",
        str(1, "turnId"),
        str(2, "requestId"),
        str(3, "interactionId"),
    )

    val TURN_SETTLED: Schema = msg(
        "agent.v1.AgentHostTurnSettled",
        str(1, "turnId"),
        enum(2, "outcome"),
        str(3, "detail"),
    )

    val SESSION_EVENT: Schema = msg(
        "agent.v1.AgentHostSessionEvent",
        u64(1, "eventId"),
        bool(14, "keepalive"),
        sub(2, "interactionUpdate") { INTERACTION_UPDATE },
        sub(3, "conversationState") { AgentSchemas.CONVERSATION_STATE },
        sub(4, "turnStarted") { TURN_STARTED },
        sub(8, "turnAwaitingInput") { TURN_AWAITING_INPUT },
        sub(9, "turnSettled") { TURN_SETTLED },
    )

    val GET_BLOBS_REQUEST: Schema = msg(
        "agent.v1.GetAgentHostSessionBlobsRequest",
        str(1, "sessionId"),
        bytes(2, "blobIds", repeated = true),
        u64(3, "maxResponseBytes"),
    )

    val SESSION_BLOB: Schema = msg(
        "agent.v1.AgentHostSessionBlob",
        bytes(1, "blobId"),
        bytes(2, "data"),
    )

    val GET_BLOBS_RESPONSE: Schema = msg(
        "agent.v1.GetAgentHostSessionBlobsResponse",
        sub(1, "blobs", repeated = true) { SESSION_BLOB },
        bytes(2, "missingBlobIds", repeated = true),
        bytes(3, "remainingBlobIds", repeated = true),
        bytes(4, "oversizeBlobIds", repeated = true),
    )

    val WATCH_REQUEST: Schema = msg(
        "agent.v1.WatchAgentHostSessionsRequest",
        str(1, "workspacePathsFilter", repeated = true),
        bool(2, "matchAnyWorkspaceRoot"),
        str(3, "ownerClientInstanceId"),
        bool(4, "includeOwnerUnrootedSessions"),
    )

    val WATCH_SNAPSHOT: Schema = msg(
        "agent.v1.AgentHostSessionsSnapshot",
        sub(1, "sessions", repeated = true) { SESSION },
    )

    val WATCH_ADDED: Schema = msg(
        "agent.v1.AgentHostSessionAdded",
        sub(1, "session") { SESSION },
    )

    val WATCH_UPDATED: Schema = msg(
        "agent.v1.AgentHostSessionUpdated",
        sub(1, "session") { SESSION },
    )

    val WATCH_DELETED: Schema = msg(
        "agent.v1.AgentHostSessionDeleted",
        str(1, "sessionId"),
    )

    val WATCH_EVENT: Schema = msg(
        "agent.v1.WatchAgentHostSessionsEvent",
        sub(1, "snapshot") { WATCH_SNAPSHOT },
        sub(2, "added") { WATCH_ADDED },
        sub(3, "updated") { WATCH_UPDATED },
        sub(4, "deleted") { WATCH_DELETED },
        bool(5, "keepalive"),
    )
}
