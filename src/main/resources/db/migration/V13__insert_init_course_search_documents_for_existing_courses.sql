INSERT INTO course_search_documents (course_id, retrieval_text, status)
SELECT c.id, '', 'INIT'
FROM courses c
WHERE c.active = true
  AND c.is_shared = true
  AND NOT EXISTS (
    SELECT 1 FROM course_search_documents d WHERE d.course_id = c.id
  );
