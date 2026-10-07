package com.cursorforandroid.data.api.proto

import com.cursorforandroid.data.api.proto.ProtoWire.Kind
import com.cursorforandroid.data.api.proto.ProtoWire.Schema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The inverse of [ProtoWire] for the fixtures: a proto3-JSON object written as protobuf binary against a [Schema],
 * so the fault server can hand out blobs the way the account does. Production encode lives on [ProtoWire].
 */
object ProtoEncoder {
    fun encode(json: JsonObject, schema: Schema, unknown: List<Triple<Int, Kind, JsonElement>> = emptyList()): ByteArray =
        ProtoWire.encode(json, schema, unknown)
}
