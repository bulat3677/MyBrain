package core

data class ToolCall(val id: String, val name: String, val arguments: Map<String, String>)
data class ToolSpec(val name: String, val description: String)

sealed interface AgentMessage {
    data class User(val content: String) : AgentMessage
    data class Assistant(val content: String) : AgentMessage
    data class ToolResult(
        val callId: String, val toolName: String, val content: String, val isError: Boolean
    ) : AgentMessage
}

data class ModelResponse(val text: String = "", val toolCalls: List<ToolCall> = emptyList())

interface ModelProvider {
    fun complete(messages: List<AgentMessage>, tools: List<ToolSpec>): ModelResponse
}

interface AgentTool {
    val name: String
    val description: String
    fun execute(arguments: Map<String, String>): String
}

fun interface ToolPolicy {
    fun isAllowed(tool: AgentTool, arguments: Map<String, String>): Boolean
}

class ToolRegistry(tools: List<AgentTool>) {
    private val byName: Map<String, AgentTool> = tools.associateBy { it.name }

    init {
        require(tools.all { it.name.isNotBlank() }) { "Tool names must not be blank" }
        require(byName.size == tools.size) { "Tool names must be unique" }
    }

    fun specs(): List<ToolSpec> = byName.values.map { ToolSpec(it.name, it.description) }
    fun find(name: String): AgentTool? = byName[name]
}

class AgentLimitExceeded(message: String) : IllegalStateException(message)

class AgentLoop(
    private val provider: ModelProvider,
    private val registry: ToolRegistry,
    private val policy: ToolPolicy,
    private val maxTurns: Int = 8,
    private val maxToolCalls: Int = 12
) {
    init {
        require(maxTurns > 0) { "maxTurns must be positive" }
        require(maxToolCalls > 0) { "maxToolCalls must be positive" }
    }

    fun run(userInput: String): String {
        require(userInput.isNotBlank()) { "User input must not be blank" }
        val messages = mutableListOf<AgentMessage>(AgentMessage.User(userInput))
        var callsExecuted = 0

        repeat(maxTurns) {
            val response = provider.complete(messages.toList(), registry.specs())
            if (response.toolCalls.isEmpty()) {
                if (response.text.isBlank()) {
                    throw IllegalStateException("Provider returned neither text nor tool calls")
                }
                messages += AgentMessage.Assistant(response.text)
                return response.text
            }

            response.toolCalls.forEach { call ->
                if (callsExecuted >= maxToolCalls) {
                    throw AgentLimitExceeded("Tool-call limit ($maxToolCalls) exceeded")
                }
                val tool = registry.find(call.name)
                val result = when {
                    tool == null -> AgentMessage.ToolResult(
                        call.id, call.name, "Tool not found: ${call.name}", true
                    )
                    !policy.isAllowed(tool, call.arguments) -> AgentMessage.ToolResult(
                        call.id, call.name, "Permission denied for tool: ${call.name}", true
                    )
                    else -> try {
                        AgentMessage.ToolResult(call.id, call.name, tool.execute(call.arguments), false)
                    } catch (error: Exception) {
                        AgentMessage.ToolResult(
                            call.id, call.name,
                            "Tool failed: ${error.message ?: error::class.simpleName}", true
                        )
                    }
                }
                messages += result
                callsExecuted++
            }
        }
        throw AgentLimitExceeded("Agent turn limit ($maxTurns) exceeded")
    }
}
