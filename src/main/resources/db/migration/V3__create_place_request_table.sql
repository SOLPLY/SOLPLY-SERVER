CREATE TABLE place_request (
                       id BIGSERIAL PRIMARY KEY,
                       place_Name VARCHAR(255) UNIQUE,
                       main_Tag_Id BIGINT NOT NULL,
                       sub_TagA_Ids BIGINT NOT NULL,
                       sub_TagB_Ids BIGINT NOT NULL,
                       reason TEXT NOT NULL
);

CREATE TRIGGER update_place_request_updated_at BEFORE UPDATE ON place_request FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();