package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.dto.CreateAgentRequestDto
import com.cursorforandroid.data.api.dto.CreateRunRequestDto
import com.cursorforandroid.data.api.dto.PromptDto
import com.cursorforandroid.domain.McpOAuth
import com.cursorforandroid.domain.McpServer
import com.cursorforandroid.domain.McpTransport
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The inline `mcpServers[]` shape, checked against the examples in the Cloud Agents API reference. */
class McpServerEncodingTest {

    private val linear = McpServer(
        id = "a",
        name = "linear",
        transport = McpTransport.Http,
        url = "https://mcp.linear.app/sse",
        headers = mapOf("Authorization" to "Bearer YOUR_LINEAR_API_KEY"),
    )
    private val github = McpServer(
        id = "b",
        name = "github",
        transport = McpTransport.Stdio,
        command = "npx",
        args = listOf("-y", "@modelcontextprotocol/server-github"),
        env = mapOf("GITHUB_TOKEN" to "YOUR_GITHUB_TOKEN"),
    )

    @Test
    fun `http servers carry url and headers, stdio servers command, args and env`() {
        val body = CreateAgentRequestDto(prompt = PromptDto("Add a README"), mcpServers = listOf(linear, github).toInlineServers())
        assertThat(CursorJson.encodeToString(CreateAgentRequestDto.serializer(), body)).isEqualTo(
            """{"prompt":{"text":"Add a README"},"mcpServers":[""" +
                """{"name":"linear","type":"http","url":"https://mcp.linear.app/sse","headers":{"Authorization":"Bearer YOUR_LINEAR_API_KEY"}},""" +
                """{"name":"github","type":"stdio","command":"npx","args":["-y","@modelcontextprotocol/server-github"],"env":{"GITHUB_TOKEN":"YOUR_GITHUB_TOKEN"}}""" +
                """]}""",
        )
    }

    @Test
    fun `empty header, arg and env collections are left out`() {
        val bare = McpServer(id = "c", name = " docs ", transport = McpTransport.Http, url = " https://example.com/mcp ").toDto()
        assertThat(CursorJson.encodeToString(com.cursorforandroid.data.api.dto.McpServerDto.serializer(), bare))
            .isEqualTo("""{"name":"docs","type":"http","url":"https://example.com/mcp"}""")
    }

    @Test
    fun `only enabled servers go out and none at all omits the field`() {
        val servers = listOf(linear.copy(enabled = false), github)
        assertThat(servers.toInlineServers()?.map { it.name }).containsExactly("github")
        assertThat(listOf(linear.copy(enabled = false)).toInlineServers()).isNull()

        val followUp = CreateRunRequestDto(prompt = PromptDto("Also add troubleshooting steps"), mcpServers = emptyList<McpServer>().toInlineServers())
        assertThat(CursorJson.encodeToString(CreateRunRequestDto.serializer(), followUp)).isEqualTo("""{"prompt":{"text":"Also add troubleshooting steps"}}""")
    }

    @Test
    fun `sse servers carry type sse with url and optional oauth`() {
        val docs = McpServer(
            id = "d",
            name = "docs",
            transport = McpTransport.Sse,
            url = "https://example.com/sse",
            auth = McpOAuth(clientId = "cid", clientSecret = "secret", scopes = listOf("read", "write")),
        )
        assertThat(CursorJson.encodeToString(com.cursorforandroid.data.api.dto.McpServerDto.serializer(), docs.toDto()))
            .isEqualTo(
                """{"name":"docs","type":"sse","url":"https://example.com/sse",""" +
                    """"auth":{"CLIENT_ID":"cid","CLIENT_SECRET":"secret","scopes":["read","write"]}}""",
            )
        val bare = docs.copy(auth = McpOAuth(clientId = "cid")).toDto()
        assertThat(CursorJson.encodeToString(com.cursorforandroid.data.api.dto.McpServerDto.serializer(), bare))
            .isEqualTo("""{"name":"docs","type":"sse","url":"https://example.com/sse","auth":{"CLIENT_ID":"cid"}}""")
    }
}
