package io.sessioninsights.analysis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Consumes analysis requests; runs signal checks, LLM analysis (Ollama default) and guardrails. */
@SpringBootApplication
public class AnalysisWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalysisWorkerApplication.class, args);
    }
}
