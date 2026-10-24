package com.airtribe.wallet.service;

public interface LlmClient {
    /**
     * Sends prompt to LLM and returns raw text response.
     */
    String complete(String systemPrompt, String userPrompt);
}
