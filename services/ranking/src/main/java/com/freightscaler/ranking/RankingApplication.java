package com.freightscaler.ranking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RankingApplication {

    public static void main(String[] args) {
        SpringApplication.run(RankingApplication.class, args);
    }
}
