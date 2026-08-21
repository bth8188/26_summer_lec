package com.lecture.rag.day3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.PathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import reactor.core.publisher.Flux;

/**
 * Day3 캡스톤 데모 백엔드 — Next.js(Vercel AI SDK) 프론트가 직접 호출하는 API 둘.
 * Lab1.4(PdfRagApiController)와 동일한 파이프라인(PDF -> 청킹 -> SimpleVectorStore)이지만,
 * 채팅 응답을 스트리밍으로 내려준다는 점이 다르다.
 */
@RestController
@RequestMapping("/api")
public class ChatApiController {

    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;

    private volatile VectorStore vectorStore;

    public ChatApiController(EmbeddingModel embeddingModel, ChatModel chatModel) {
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
    }

    public record IndexResponse(String fileName, int chunkCount) {}

    public record ChatRequest(String question) {}

    @PostMapping("/index")
    public IndexResponse index(@RequestParam("file") MultipartFile file) throws IOException {
        Path tempFile = Files.createTempFile("day3-upload-", ".pdf");
        file.transferTo(tempFile);
        try {
            PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(new PathResource(tempFile));
            List<Document> documents = pdfReader.get();

            TokenTextSplitter splitter = TokenTextSplitter.builder().withChunkSize(300).build();
            List<Document> chunks = splitter.apply(documents);

            VectorStore newStore = SimpleVectorStore.builder(embeddingModel).build();
            newStore.add(chunks);
            this.vectorStore = newStore;

            return new IndexResponse(file.getOriginalFilename(), chunks.size());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request) {
        VectorStore currentStore = this.vectorStore;
        if (currentStore == null) {
            return Flux.just("아직 업로드된 PDF가 없습니다. 먼저 문서를 업로드해주세요.");
        }

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        항상 한국어로 답변하세요.
                        주어진 컨텍스트에서 답을 찾을 수 없으면 지어내지 말고 모른다고 답하세요.
                        """)
                .build();

        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(currentStore).build();

        return chatClient.prompt()
                .advisors(qaAdvisor)
                .user(request.question())
                .stream()
                .content();
    }
}
