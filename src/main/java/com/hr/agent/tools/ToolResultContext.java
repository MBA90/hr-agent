package com.hr.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Thread-local store that tools write their JSON result into so the controller
 * can attach it to the response without relying on the LLM to echo it back.
 *
 * Works because LangChain4j AiServices (non-streaming) executes tool calls
 * synchronously in the same thread as the HTTP request.
 */
@Component
public class ToolResultContext {

    private static final ThreadLocal<JsonNode> HOLDER = new ThreadLocal<>();

    public void capture(JsonNode data) {
        HOLDER.set(data);
    }

    public JsonNode retrieve() {
        JsonNode data = HOLDER.get();
        HOLDER.remove();
        return data;
    }
}
