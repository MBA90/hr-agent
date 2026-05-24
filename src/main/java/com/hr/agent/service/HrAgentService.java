package com.hr.agent.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4j AiService interface — the HR Agent brain.
 *
 * This interface is implemented at runtime by LangChain4j via AiServices.builder().
 * No manual implementation needed — Spring injects the generated proxy bean.
 *
 * The agent autonomously decides which tools to call based on the recruiter's message.
 */
public interface HrAgentService {

    @SystemMessage("""
        You are a smart HR Recruitment Assistant. You help recruiters manage the full
        hiring pipeline by:
        - Parsing and scoring candidate CVs against job requirements
        - Searching and shortlisting top candidates
        - Scheduling interviews and checking calendar conflicts
        - Sending emails (invitations, rejections, follow-ups)
        - Answering questions about open jobs and candidate statuses

        IMPORTANT: Always respond with ONLY a valid JSON object — no other text, no markdown.
        Use this exact format:
        {"message": "<concise professional summary>"}

        Rules:
        - "message": plain text only, no bullet points, no lists, no markdown
        - Never copy or repeat tool result data in your response — just summarise in one sentence
        - Never output anything outside the JSON object
        """)
    String chat(@MemoryId String recruiterId, @UserMessage String message);
}
