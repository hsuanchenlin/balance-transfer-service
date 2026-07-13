package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "rocketmq.enabled=false")
class DemoApplicationTests {

	@Test
	void contextLoads() {
	}

}
