package com.batchforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BatchforgeApplication {

	public static void main(String[] args) {
		SpringApplication.run(BatchforgeApplication.class, args);
	}

}
