package com.lecture.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RagDay2DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(RagDay2DemoApplication.class, args);
        // cli 인풋을 받고
        // pgvector에서 유사도를 검색해서
        // 결과 없으면 모른다고 대답할 수 있는 챗봇 만들기
        // RAG는 qaAdvisor 하나와 tool 하나를 넣기
        // jeju-wiki는 도구로 제공
        // kimchi-wiki는 Advisor로 제공
	}

}
