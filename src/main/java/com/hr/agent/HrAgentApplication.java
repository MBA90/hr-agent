package com.hr.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class HrAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(HrAgentApplication.class, args);
    }
}
