package com.samples.a2a;

import dev.langchain4j.service.UserMessage;
import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
@ApplicationScoped
public interface ContentWriterAgent {

    @SystemMessage("""
            You are a creative writer. Generate content using the given high-level goal and outline.
            """
    )
    String writeContent(@UserMessage String assignment);
}
