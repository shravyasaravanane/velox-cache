package com.velox.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Tier 5: the demo. Every other tier built a library; this is what makes {@code
 * VeloxCache} something a web server actually uses, backed by a real (if embedded by
 * default) database, over real HTTP.
 */
@SpringBootApplication
@EnableTransactionManagement
@EnableScheduling
public class VeloxServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(VeloxServerApplication.class, args);
    }
}
