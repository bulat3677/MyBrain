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
    fun blankInputIsRejected() {
        val provider = QueueProvider(ModelResponse(text = "unused"))
        val loop = AgentLoop(provider, ToolRegistry(emptyList()), ToolPolicy { _, _ -> true })
        assertFailsWith<IllegalArgumentException> { loop.run("  ") }
        assertFalse(provider.seenMessages.isNotEmpty())
    }
}
