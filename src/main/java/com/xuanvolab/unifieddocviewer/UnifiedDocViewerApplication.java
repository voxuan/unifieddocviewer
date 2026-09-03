package com.xuanvolab.unifieddocviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class UnifiedDocViewerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UnifiedDocViewerApplication.class, args);
    }
}
