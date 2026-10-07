package com.cursorforandroid.data.api.proto

import com.cursorforandroid.data.api.proto.ProtoWire.Field
import com.cursorforandroid.data.api.proto.ProtoWire.Kind
import com.cursorforandroid.data.api.proto.ProtoWire.Schema

/**
 * The `agent.v1` messages wrapped as protobuf `data` on `aiserver.v1.CallAgentHost`, from Cursor 3.23.23's
 * `workbench.glass.main.js` descriptors. Connect JSON carries the outer CallAgentHost request; the inner payload is
 * binary protobuf, so these schemas are for [ProtoWire] encode/decode of that `data` field.
 */
object AgentHostSchemas {

    private fun msg(name: String, vararg fields: Field) = Schema(name, fields.toList())
    private fun str(no: Int, name: String, repeated: Boolean = false) = Field(no, name, Kind.STRING, repeated)
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
}
