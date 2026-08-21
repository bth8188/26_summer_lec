package com.lecture.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RagDay3DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(RagDay3DemoApplication.class, args);
	}

}

//Rest endpoint는 제공
//AbstractRagPipeline의 훅 완성
//rewriteOrigin
