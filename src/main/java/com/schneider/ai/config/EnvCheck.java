package com.schneider.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class EnvCheck implements CommandLineRunner {

    @Value("${CONFLUENCE_URL:NOT_FOUND}")
    private String confluenceUrl;

    @Override
    public void run(String... args) {
        System.out.println("CONFLUENCE_URL=" + confluenceUrl);
    }
}