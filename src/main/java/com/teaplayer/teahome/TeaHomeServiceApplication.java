package com.teaplayer.teahome;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TeaHomeServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(TeaHomeServiceApplication.class, args);
	}

}
