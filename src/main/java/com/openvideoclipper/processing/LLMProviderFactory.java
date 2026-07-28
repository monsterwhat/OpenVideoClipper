package com.openvideoclipper.processing;

import com.openvideoclipper.service.LLMHighlightService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class LLMProviderFactory {

    @Inject
    LLMHighlightService llmService;

    public LLMProvider getProvider() {
        return llmService;
    }
}
