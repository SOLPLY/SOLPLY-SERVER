-- 기존 장소 중 place_search_documents가 없는 장소에 INIT 레코드 삽입
INSERT INTO place_search_documents (place_id, retrieval_text, status, created_at, updated_at)
SELECT p.id, '', 'INIT', NOW(6), NOW(6)
FROM places p
WHERE p.id NOT IN (SELECT place_id FROM place_search_documents);
