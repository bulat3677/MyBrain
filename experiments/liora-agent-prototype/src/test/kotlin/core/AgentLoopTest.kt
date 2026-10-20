package core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentLoopTest {
    private class FakeTool : AgentTool {
        override val name = "search_notes"
        override val description = "Search demo notes"
        var executions = 0
        override fun execute(arguments: Map<String, String>): String {
            executions++
            return "found:${arguments["query"]}"
        }
    }

    private class QueueProvider(private vararg val responses: ModelResponse) : ModelProvider {
        val seenMessages = mutableListOf<List<AgentMessage>>()
        val seenTools = mutableListOf<List<ToolSpec>>()
        private var index = 0
        override fun complete(messages: List<AgentMessage>, tools: List<ToolSpec>): ModelResponse {
            seenMessages += messages
            seenTools += tools
            return responses.getOrElse(index++) { error("Unexpected provider call") }
        }
    }

    @Test
    fun toolResultReturnsToModelBeforeFinalAnswer() {
        val tool = FakeTool()
        val provider = QueueProvider(
            ModelResponse(toolCalls = listOf(ToolCall("c1", "search_notes", mapOf("query" to "Liora")))),
            ModelResponse(text = "Found Liora notes")
        )
        val loop = AgentLoop(provider, ToolRegistry(listOf(tool)), ToolPolicy { _, _ -> true })
        assertEquals("Found Liora notes", loop.run("Find Liora notes"))
        assertEquals(1, tool.executions)
        assertTrue(provider.seenMessages[1].any {
            it is AgentMessage.ToolResult && it.callId == "c1" && it.content == "found:Liora"
        })
        assertEquals("search_notes", provider.seenTools[0].single().name)
    }

    @Test
    fun unknownToolIsNotExecutedAndErrorReturnsToModel() {
        val provider = QueueProvider(
            ModelResponse(toolCalls = listOf(ToolCall("c1", "delete_everything", emptyMap()))),
            ModelResponse(text = "I could not run that tool")
        )
        val loop = AgentLoop(provider, ToolRegistry(emptyList()), ToolPolicy { _, _ -> true })
        assertEquals("I could not run that tool", loop.run("Do something"))
        assertTrue(provider.seenMessages[1].any {
            it is AgentMessage.ToolResult && it.isError && it.content.contains("Tool not found")
        })
    }

    @Test
    fun policyDenialPreventsExecution() {
        val tool = FakeTool()
        val provider = QueueProvider(
            ModelResponse(toolCalls = listOf(ToolCall("c1", "search_notes", mapOf("query" to "private")))),
            ModelResponse(text = "Permission was denied")
        )
        val loop = AgentLoop(provider, ToolRegistry(listOf(tool)), ToolPolicy { _, _ -> false })
        assertEquals("Permission was denied", loop.run("Search private notes"))
        assertEquals(0, tool.executions)
        assertTrue(provider.seenMessages[1].any {
            it is AgentMessage.ToolResult && it.isError && it.content.contains("Permission denied")
        })
    }

    @Test
    fun toolCallLimitStopsRunawayLoop() {
        val provider = QueueProvider(
            ModelResponse(toolCalls = listOf(ToolCall("c1", "missing", emptyMap()))),
            ModelResponse(toolCalls = listOf(ToolCall("c2", "missing", emptyMap())))
        )
        val loop = AgentLoop(provider, ToolRegistry(emptyList()), ToolPolicy { _, _ -> true }, maxToolCalls = 1)
        assertFailsWith<AgentLimitExceeded> { loop.run("Repeat forever") }
    }

    @Test
    fun malformedBatchIsRejectedBeforeAnyToolExecutes() {
        val tool = FakeTool()
        val provider = QueueProvider(
            ModelResponse(toolCalls = listOf(
                ToolCall("same", "search_notes", mapOf("query" to "first")),
                ToolCall("same", "search_notes", mapOf("query" to "second"))
            ))
        )
        val loop = AgentLoop(provider, ToolRegistry(listOf(tool)), ToolPolicy { _, _ -> true })
        assertFailsWith<IllegalArgumentException> { loop.run("Run tools") }
        assertEquals(0, tool.executions)
    }

    @Test
    fun policyFailureDefaultsToDeny() {
        val tool = FakeTool()
        val provider = QueueProvider(
            ModelResponse(toolCalls = listOf(ToolCall("c1", "search_notes", mapOf("query" to "private")))),
            ModelResponse(text = "Denied")
        )
        val loop = AgentLoop(provider, ToolRegistry(listOf(tool)), ToolPolicy { _, _ ->
            throw IllegalStateException("sensitive policy detail")
        })
        assertEquals("Denied", loop.run("Search"))
        assertEquals(0, tool.executions)
        assertTrue(provider.seenMessages[1].any {
            it is AgentMessage.ToolResult && it.isError && it.content.contains("Permission denied")
        })
    }

    @Test
    fun toolOutputIsBoundedBeforeReturningToModel() {
        val tool = object : AgentTool {
            override val name = "large_output"
            override val description = "Returns a long string"
            override fun execute(arguments: Map<String, String>) = "x".repeat(50)
        }
        val provider = QueueProvider(
            ModelResponse(toolCalls = listOf(ToolCall("c1", "large_output", emptyMap()))),
            ModelResponse(text = "Handled")
        )
        val loop = AgentLoop(
            provider, ToolRegistry(listOf(tool)), ToolPolicy { _, _ -> true },
            maxToolOutputChars = 10
        )
        assertEquals("Handled", loop.run("Get output"))
        val result = provider.seenMessages[1].filterIsInstance<AgentMessage.ToolResult>().single()
        assertEquals("xxxxxxxxxx…[truncated]", result.content)
    }

    @Test
    fun oversizedInputIsRejectedBeforeProviderCall() {
        val provider = QueueProvider(ModelResponse(text = "unused"))
        val loop = AgentLoop(
            provider, ToolRegistry(emptyList()), ToolPolicy { _, _ -> true },
            maxInputChars = 4
        )
        assertFailsWith<IllegalArgumentException> { loop.run("12345") }
        assertFalse(provider.seenMessages.isNotEmpty())
    }

    @Test
    fun toolExceptionMessageIsNotExposedToModel() {
        val tool = object : AgentTool {
            override val name = "broken_tool"
            override val description = "Always fails"
            override fun execute(arguments: Map<String, String>): String {
                throw IllegalStateException("api-key=do-not-leak")
            }
        }
        val provider = QueueProvider(
            ModelResponse(toolCalls = listOf(ToolCall("c1", "broken_tool", emptyMap()))),
            ModelResponse(text = "Failed safely")
        )
        val loop = AgentLoop(provider, ToolRegistry(listOf(tool)), ToolPolicy { _, _ -> true })
        assertEquals("Failed safely", loop.run("Try it"))
        val result = provider.seenMessages[1].filterIsInstance<AgentMessage.ToolResult>().single()
        assertEquals("Tool execution failed", result.content)
        assertFalse(result.content.contains("do-not-leak"))
    }

    @Test
    fun blankInputIsRejected() {
        val provider = QueueProvider(ModelResponse(text = "unused"))
        val loop = AgentLoop(provider, ToolRegistry(emptyList()), ToolPolicy { _, _ -> true })
        assertFailsWith<IllegalArgumentException> { loop.run("  ") }
        assertFalse(provider.seenMessages.isNotEmpty())
    }
}
