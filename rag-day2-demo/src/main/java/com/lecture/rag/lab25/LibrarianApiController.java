package com.lecture.rag.lab25;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Lab 2.5 — 사서 챗봇의 Swagger 버전. 로직은 LibrarianService를 그대로 쓰고 HTTP만 붙인다.
 * lab24의 ChatbotApiController와 같은 구성.
 *
 * 실행: 1) docker compose up -d
 *       2) ./run.sh lab25-api
 * 접속: http://localhost:8080/swagger-ui/index.html
 */
@RestController
@Profile("lab25-api")
@RequestMapping("/api/librarian")
@Tag(name = "Lab2.5 사서 챗봇",
        description = "관계 테이블(document)은 SQL 도구로, 문서 본문은 벡터 도구로 조회한다. "
                + "두 저장소는 document.id ↔ 벡터 metadata의 document_id로 이어진다.")
public class LibrarianApiController {

    private final LibrarianService librarian;

    public LibrarianApiController(LibrarianService librarian) {
        this.librarian = librarian;
    }

    @Operation(summary = "사서에게 질문하기",
            description = "응답의 catalogCalls / contentCalls를 보면 모델이 어느 도구를 골랐는지 알 수 있다. "
                    + "카탈로그 질문 예: \"어떤 자료들 갖고 있어?\" / 본문 질문 예: \"제주도 면적이 얼마야?\"")
    @GetMapping("/ask")
    public LibrarianService.Answer ask(
            @Parameter(description = "자료 목록에 대한 질문이거나, 자료 본문 내용에 대한 질문",
                    example = "제주도 면적이 얼마야?")
            @RequestParam(defaultValue = "제주도 면적이 얼마야?") String question) {
        return librarian.ask(question);
    }

    @Operation(summary = "카탈로그 목록 조회",
            description = "document 테이블을 그대로 보여준다. LLM을 거치지 않는 순수 SQL 조회라, "
                    + "챗봇이 listDocuments 도구로 받아보는 것과 같은 데이터다.")
    @GetMapping("/documents")
    public List<Map<String, Object>> documents(
            @Parameter(description = "카테고리로 좁히려면 지정 (manual, research, terms, wiki, opensource). 비우면 전체.",
                    example = "wiki")
            @RequestParam(required = false) String category) {
        return librarian.listDocuments(category);
    }

    @Operation(summary = "조인 검증",
            description = "카탈로그에 기록된 청크 수와 벡터 테이블에서 document_id로 실제로 세어본 청크 수를 비교한다. "
                    + "joinConsistent가 true면 두 저장소가 같은 키로 제대로 이어져 있다는 뜻.")
    @GetMapping("/documents/{id}/chunks")
    public Map<String, Object> chunks(
            @Parameter(description = "카탈로그의 문서 번호", example = "7")
            @PathVariable int id) {
        return librarian.chunkReport(id);
    }
}
