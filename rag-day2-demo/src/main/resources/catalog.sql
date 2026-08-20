-- 자료실 카탈로그. doc_id로 벡터스토어의 본문을 찾아간다.

create table if not exists lib_category (
    category_id varchar(20) primary key,
    category_name varchar(100) not null,
    shelf varchar(20) not null
);

create table if not exists lib_document (
    doc_id varchar(20) primary key,
    title varchar(200) not null,
    category_id varchar(20) not null references lib_category(category_id),
    author varchar(100),
    source_file varchar(200) not null
);

insert into lib_category (category_id, category_name, shelf) values
    ('MANUAL', '제품 매뉴얼', 'A-1'),
    ('TERMS', '약관 및 규정', 'A-3'),
    ('DEV', '개발 문서', 'B-2'),
    ('OSS', '오픈소스 문서', 'B-4'),
    ('PAPER', '연구 논문', 'C-3'),
    ('WIKI', '백과사전', 'D-1')
on conflict (category_id) do nothing;

insert into lib_document (doc_id, title, category_id, author, source_file) values
    ('D001', '이커머스 고객센터 운영 매뉴얼', 'MANUAL', null, '1-ecommerce-manual.pdf'),
    ('D002', 'Agentic RAG 서베이', 'DEV', null, '2-devdocs-agentic-rag.pdf'),
    ('D003', 'LLM 에이전트 평가 연구', 'PAPER', null, '3-research-llm-agent-eval.pdf'),
    ('D004', '스타트업레시피 서비스 이용약관', 'TERMS', '주식회사 미디어레시피', '4-terms-startuprecipe.pdf'),
    ('D005', '국민건강보험', 'WIKI', '위키백과', '5-wiki-nhis.pdf'),
    ('D006', '제주도', 'WIKI', '위키백과', '6-wiki-jeju.pdf'),
    ('D007', '김치', 'WIKI', '위키백과', '7-wiki-kimchi.pdf'),
    ('D008', 'Spring AI 프로젝트 README', 'OSS', 'Spring AI', '8-opensource-spring-ai-readme.pdf')
on conflict (doc_id) do nothing;
