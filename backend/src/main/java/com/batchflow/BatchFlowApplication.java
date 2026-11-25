package com.batchflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BatchFlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(BatchFlowApplication.class, args);
    }
}
