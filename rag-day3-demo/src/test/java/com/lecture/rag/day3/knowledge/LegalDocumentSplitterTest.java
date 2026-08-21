package com.lecture.rag.day3.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class LegalDocumentSplitterTest {

    @Test
    void splitsAtArticleBoundariesAndRecognizesArticleNumbers() {
        String text = """
                은행법
                [시행 2026. 7. 1.]
                제1조(목적) 이 법은 예금자를 보호하는 것을 목적으로 한다.
                제2조의2(정의) 이 조에서 사용하는 용어의 뜻은 다음과 같다.
                """;

        List<String> chunks = new LegalDocumentSplitter(500).split(text);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(1)).startsWith("제1조(목적)");
        assertThat(chunks.get(2)).startsWith("제2조의2(정의)");
        assertThat(LegalDocumentSplitter.articleNumber(chunks.get(2))).isEqualTo("제2조의2");
    }

    @Test
    void repeatsArticleHeadingWhenALongArticleIsSplit() {
        String sentence = "은행은 대출금리 산정에 필요한 기준과 절차를 마련하여야 한다. ";
        String text = "제30조의3(대출금리의 산정) " + sentence.repeat(20);

        List<String> chunks = new LegalDocumentSplitter(220).split(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk).startsWith("제30조의3(대출금리의 산정)");
            assertThat(LegalDocumentSplitter.articleNumber(chunk)).isEqualTo("제30조의3");
        });
    }

    @Test
    void fallsBackToRecursiveSplittingForPressReleases() {
        String text = "은행 신규대출의 금리 부담을 완화합니다. ".repeat(30);

        List<String> chunks = new LegalDocumentSplitter(220).split(text);

        assertThat(chunks).hasSizeGreaterThan(1).allMatch(chunk -> !chunk.isBlank());
    }
}
