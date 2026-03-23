package com.prepline.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.prepline")
@EnableJpaRepositories(basePackages = "com.prepline")
@EntityScan(basePackages = "com.prepline")
public class PreplineAuthApplication {

	public static void main(String[] args) {
		SpringApplication.run(PreplineAuthApplication.class, args);
	}
}