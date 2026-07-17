package com.stocksugg.gemini;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;

/**
 * Thin wrapper around the Google GenAI SDK for Gemini Developer API calls.
 */
public final class GeminiService implements AutoCloseable {

    private final Client client;
    private final String model;

    public GeminiService(GeminiConfig config) {
        this.client = Client.builder().apiKey(config.apiKey()).build();
        this.model = config.model();
    }

    /** Sends a prompt and returns the model's text response. */
    public String generateText(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt must not be blank.");
        }
        GenerateContentResponse response = client.models.generateContent(model, prompt, null);
        String text = response.text();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Gemini returned an empty response.");
        }
        return text;
    }

    /** Sends a prompt with optional generation settings (temperature, max tokens, etc.). */
    public String generateText(String prompt, GenerateContentConfig config) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt must not be blank.");
        }
        GenerateContentResponse response = client.models.generateContent(model, prompt, config);
        String text = response.text();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Gemini returned an empty response.");
        }
        return text;
    }

    /** Simple connectivity check against the configured model. */
    public String ping() {
        return generateText("Reply with exactly: ok");
    }

    public String model() {
        return model;
    }

    @Override
    public void close() {
        client.close();
    }
}
