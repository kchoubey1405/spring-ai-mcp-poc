package com.schneider.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SpringAiMcpPocApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiMcpPocApplication.class, args);
    }
}
