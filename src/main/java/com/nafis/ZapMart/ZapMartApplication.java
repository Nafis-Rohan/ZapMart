package com.nafis.ZapMart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ZapMartApplication {

	public static void main(String[] args) {
		SpringApplication.run(ZapMartApplication.class, args);
	}

}
