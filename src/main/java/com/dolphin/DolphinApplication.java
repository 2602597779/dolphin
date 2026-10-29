package com.dolphin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DolphinApplication {

    public static void main(String[] args) {
        SpringApplication.run(DolphinApplication.class, args);
    }
}
