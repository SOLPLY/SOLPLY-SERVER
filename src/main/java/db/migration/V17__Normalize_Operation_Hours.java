package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.sopt.solply_server.domain.place.dto.OperationTimeDto;
import org.sopt.solply_server.domain.place.util.OperationHourParser;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class V17__Normalize_Operation_Hours extends BaseJavaMigration {
	@Override
	public void migrate(Context context) throws Exception {
		Connection connection = context.getConnection();
		// places 테이블 조회
		try (Statement select = connection.createStatement();
			 ResultSet rs = select.executeQuery("SELECT id, opening_hours FROM places WHERE id <= 320")) {

			String insertSql = "INSERT INTO operation_time_slots (place_id, day_of_week, start_time, end_time,end_next_day, is_day_off,description,created_at,updated_at,last_order_time) VALUES (?,?,?,?,?,?,?,?,?,?)";

			try (PreparedStatement pstmt = connection.prepareStatement(insertSql)) {
				OperationHourParser parser = new OperationHourParser();

				while (rs.next()) {
					long placeId = rs.getLong("id");
					String rawText = rs.getString("opening_hours");

					if (rawText == null || rawText.isBlank())
						continue;

					List<OperationTimeDto> slots = parser.parse(rawText);

					for (OperationTimeDto slot : slots) {
						pstmt.setLong(1, placeId);
						pstmt.setInt(2, slot.getDayOfWeek());
						if (slot.isDayOff()) {
							pstmt.setNull(3, Types.TIME);
							pstmt.setNull(4, Types.TIME);
						} else {
							if (slot.getStartTime() == null || slot.getEndTime() == null) {
								String message = String.format(
									"파싱 실패! [place_id: %d] [day_of_week: %d] 원문:[%s] - 시간 데이터가 null입니다.", placeId,
									slot.getDayOfWeek(), rawText);
								throw new IllegalStateException(message);
							}

							pstmt.setTime(3, Time.valueOf(slot.getStartTime()));
							pstmt.setTime(4, Time.valueOf(slot.getEndTime()));
						}
						pstmt.setBoolean(5, slot.isEndNextDay());
						pstmt.setBoolean(6, slot.isDayOff());
						if (slot.getDescription() == null || slot.getDescription().isEmpty())
							pstmt.setNull(7, Types.VARCHAR);
						else
							pstmt.setString(7, slot.getDescription());
						Timestamp now = Timestamp.valueOf(LocalDateTime.now());
						pstmt.setTimestamp(8, now);
						pstmt.setTimestamp(9, now);
						if (slot.getLastOrderTime() == null)
							pstmt.setNull(10, Types.TIME);
						else
							pstmt.setTime(10, Time.valueOf(slot.getLastOrderTime()));
						pstmt.addBatch();
					}
				}
				pstmt.executeBatch();
			}
		}
	}
}
