package org.sopt.solply_server.domain.operation.util;

import static org.assertj.core.api.AssertionsForInterfaceTypes.*;

import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.place.dto.OperationTimeDto;
import org.sopt.solply_server.domain.place.util.OperationHourParser;

public class OperationHourParserTest {
	private final OperationHourParser parser = new OperationHourParser();

	@Test
	@DisplayName("연중무휴 텍스트가 들어오면 월~일(1~7) 7개의 슬롯을 반환한다")
	void parse_everyday_test() {
		// given
		String rawText = "연중무휴";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		assertThat(result).hasSize(7);
		assertThat(result.get(0).getDescription()).isEqualTo("연중무휴");
	}

	@Test
	@DisplayName("일반 영업시간과 정기휴무가 포함된 텍스트를 정상 파싱한다")
	void parse_normal_with_holiday_test() {
		// given
		String rawText = "매일 10:00 - 22:00\n매주 월요일 정기휴무";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		// 월요일(1)은 휴무, 화~일(2~7)은 영업이므로 총 7개 슬롯이어야 함
		assertThat(result).hasSize(7);

		// 월요일 휴무 체크
		OperationTimeDto monday = result.stream().filter(r -> r.getDayOfWeek() == 1).findFirst().get();
		assertThat(monday.isDayOff()).isTrue();

		// 화요일 영업시간 체크
		OperationTimeDto tuesday = result.stream().filter(r -> r.getDayOfWeek() == 2).findFirst().get();
		assertThat(tuesday.isDayOff()).isFalse();
		assertThat(tuesday.getStartTime().toString()).isEqualTo("10:00");
	}

	@Test
	@DisplayName("서로 다른 요일 그룹(화~금 / 토~일)에 브레이크 타임이 각각 독립적으로 정확히 쪼개지는지 검증한다")
	void parse_complex_break_times_test() {
		// given
		String rawText = "월 11:40 - 13:30 \n" +
			"화 - 금 11:30 - 19:30 \n" +
			"브레이크타임 14:20 - 17:30 \n" +
			"토, 일 12:00 - 19:00 \n" +
			"브레이크타임 14:20 - 17:30";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		// [예상 슬롯 계산]
		// 1. 월요일: 단일 슬롯 (1개)
		// 2. 화~금: 브레이크 적용으로 하루 2개씩 쪼개짐 (4일 * 2 = 8개)
		// 3. 토~일: 브레이크 적용으로 하루 2개씩 쪼개짐 (2일 * 2 = 4개)
		// 총 13개의 슬롯이 나와야 정상입니다.
		assertThat(result).hasSize(13);

		// 1️⃣ 월요일(1) 검증: 단일 슬롯이며 시간이 맞는지
		List<OperationTimeDto> mondaySlots = result.stream()
			.filter(r -> r.getDayOfWeek() == 1).toList();

		assertThat(mondaySlots).hasSize(1);
		assertThat(mondaySlots.get(0).getStartTime().toString()).isEqualTo("11:40");
		assertThat(mondaySlots.get(0).getEndTime().toString()).isEqualTo("13:30");

		// 2️⃣ 화요일(2) 대표 검증: 첫 번째 브레이크 타임이 잘 적용되어 쪼개졌는지
		List<OperationTimeDto> tuesdaySlots = result.stream()
			.filter(r -> r.getDayOfWeek() == 2)
			.sorted(Comparator.comparing(OperationTimeDto::getStartTime)) // 오전/오후 정렬
			.toList();

		assertThat(tuesdaySlots).hasSize(2);
		assertThat(tuesdaySlots.get(0).getStartTime().toString()).isEqualTo("11:30");
		assertThat(tuesdaySlots.get(0).getEndTime().toString()).isEqualTo("14:20"); // 오전 마감
		assertThat(tuesdaySlots.get(1).getStartTime().toString()).isEqualTo("17:30"); // 오후 시작
		assertThat(tuesdaySlots.get(1).getEndTime().toString()).isEqualTo("19:30");

		// 3️⃣ 토요일(6) 대표 검증: 두 번째 브레이크 타임이 잘 적용되어 쪼개졌는지
		List<OperationTimeDto> saturdaySlots = result.stream()
			.filter(r -> r.getDayOfWeek() == 6)
			.sorted(Comparator.comparing(OperationTimeDto::getStartTime))
			.toList();

		assertThat(saturdaySlots).hasSize(2);
		assertThat(saturdaySlots.get(0).getStartTime().toString()).isEqualTo("12:00");
		assertThat(saturdaySlots.get(0).getEndTime().toString()).isEqualTo("14:20"); // 오전 마감
		assertThat(saturdaySlots.get(1).getStartTime().toString()).isEqualTo("17:30"); // 오후 시작
		assertThat(saturdaySlots.get(1).getEndTime().toString()).isEqualTo("19:00");
	}

	@Test
	@DisplayName("매일 영업 중 '평일'에만 지정된 브레이크 타임과 전체 라스트오더가 정확히 적용된다")
	void parse_weekday_specific_break_time_test() {
		// given
		String rawText = "매일 11:00 - 20:00 \n" +
			"평일 브레이크타임 15:00 - 17:00 \n" +
			"라스트오더 19:30";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		// [예상 슬롯 개수]
		// 1. 평일(1~5): 브레이크 적용으로 하루 2개씩 쪼개짐 (5일 * 2 = 10개)
		// 2. 주말(6~7): 브레이크 없이 통짜 슬롯 (2일 * 1 = 2개)
		// 총 12개의 슬롯이 나와야 정상입니다.
		assertThat(result).hasSize(12);

		// 1️⃣ 평일 대표 검증 (수요일 - 3): 슬롯이 쪼개지고, 오후 슬롯에 라스트오더가 붙었는지
		List<OperationTimeDto> wednesdaySlots = result.stream()
			.filter(r -> r.getDayOfWeek() == 3)
			.sorted(java.util.Comparator.comparing(OperationTimeDto::getStartTime))
			.toList();

		assertThat(wednesdaySlots).hasSize(2); // 슬롯 2개로 쪼개짐 확인

		// 오전 슬롯 (11:00 ~ 15:00)
		assertThat(wednesdaySlots.get(0).getStartTime().toString()).isEqualTo("11:00");
		assertThat(wednesdaySlots.get(0).getEndTime().toString()).isEqualTo("15:00");

		// 오후 슬롯 (17:00 ~ 20:00) 및 라스트오더
		assertThat(wednesdaySlots.get(1).getStartTime().toString()).isEqualTo("17:00");
		assertThat(wednesdaySlots.get(1).getEndTime().toString()).isEqualTo("20:00");
		assertThat(wednesdaySlots.get(1).getLastOrderTime().toString()).isEqualTo("19:30");

		// 2️⃣ 주말 대표 검증 (일요일 - 7): 안 쪼개지고, 라스트오더가 잘 붙었는지
		List<OperationTimeDto> sundaySlots = result.stream()
			.filter(r -> r.getDayOfWeek() == 7).toList();

		assertThat(sundaySlots).hasSize(1); // 슬롯 1개 통짜 확인

		// 종일 영업 (11:00 ~ 20:00) 및 라스트오더
		assertThat(sundaySlots.get(0).getStartTime().toString()).isEqualTo("11:00");
		assertThat(sundaySlots.get(0).getEndTime().toString()).isEqualTo("20:00");
		assertThat(sundaySlots.get(0).getLastOrderTime().toString()).isEqualTo("19:30");
	}

	@Test
	@DisplayName("Case 1: 기본 시간 포맷 파싱 및 지정된 휴무일을 제외한 나머지 요일 할당을 검증한다")
	void parse_basic_time_and_single_holiday_test() {
		// given
		String rawText = "12:00 - 19:00 \n매주 월 정기휴무";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		assertThat(result).hasSize(7);

		// 월요일(1) 휴무 확인
		OperationTimeDto monday = result.stream().filter(r -> r.getDayOfWeek() == 1).findFirst().get();
		assertThat(monday.isDayOff()).isTrue();
		assertThat(monday.getStartTime()).isNull();
		assertThat(monday.getEndTime()).isNull();

		// 화요일(2) 영업시간 확인
		OperationTimeDto tuesday = result.stream().filter(r -> r.getDayOfWeek() == 2).findFirst().get();
		assertThat(tuesday.isDayOff()).isFalse();
		assertThat(tuesday.getStartTime().toString()).isEqualTo("12:00");
		assertThat(tuesday.getEndTime().toString()).isEqualTo("19:00");
		assertThat(tuesday.isEndNextDay()).isFalse();
	}

	@Test
	@DisplayName("Case 2: 브레이크 타임을 기준으로 하루 영업시간이 2개 Row로 분리되고 라스트 오더가 마지막 타임슬롯에 적용된다")
	void parse_break_time_and_last_order_test() {
		// given
		String rawText = "11:30 - 20:30 \n브레이크타임 16:00 -17:00 \n 라스트오더 20:00\n매주 일, 월 정기휴무";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		// 일(1), 월(1) 휴무 = 2개 + 화~토(5 * 2개 슬롯) = 총 12개 슬롯
		assertThat(result).hasSize(12);

		// 일, 월 휴무 확인
		assertThat(result.stream().filter(r -> r.getDayOfWeek() == 1 || r.getDayOfWeek() == 7)
			.allMatch(OperationTimeDto::isDayOff)).isTrue();

		// 수요일(3) 대표 검증
		List<OperationTimeDto> wednesdaySlots = result.stream()
			.filter(r -> r.getDayOfWeek() == 3)
			.sorted(Comparator.comparing(OperationTimeDto::getStartTime))
			.toList();

		assertThat(wednesdaySlots).hasSize(2);

		// 오전 슬롯
		assertThat(wednesdaySlots.get(0).getStartTime().toString()).isEqualTo("11:30");
		assertThat(wednesdaySlots.get(0).getEndTime().toString()).isEqualTo("16:00");
		assertThat(wednesdaySlots.get(0).getLastOrderTime()).isNull();

		// 오후 슬롯 및 라스트오더
		assertThat(wednesdaySlots.get(1).getStartTime().toString()).isEqualTo("17:00");
		assertThat(wednesdaySlots.get(1).getEndTime().toString()).isEqualTo("20:30");
		assertThat(wednesdaySlots.get(1).getLastOrderTime().toString()).isEqualTo("20:00");
	}

	@Test
	@DisplayName("Case 3: 종료 시간이 시작 시간보다 빠를 경우 end_next_day가 TRUE로 설정되며, 언급되지 않은 요일은 생성되지 않는다")
	void parse_overnight_and_unspecified_day_test() {
		// given
		String rawText = "화 - 금 19:00 - 04:00 \n매주 월 정기휴무";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		// 월(휴무), 화, 수, 목, 금 = 총 5개 슬롯 (토, 일 누락)
		assertThat(result).hasSize(5);

		// 월요일(1) 명시적 휴무 확인
		assertThat(result.stream().filter(r -> r.getDayOfWeek() == 1).findFirst().get().isDayOff()).isTrue();

		// 토요일(6), 일요일(7) 레코드가 아예 생성되지 않았는지 검증
		boolean isWeekendPresent = result.stream()
			.anyMatch(r -> r.getDayOfWeek() == 6 || r.getDayOfWeek() == 7);
		assertThat(isWeekendPresent).isFalse();

		// 목요일(4) 대표 검증: 익일 처리(end_next_day) 검증
		OperationTimeDto thursday = result.stream().filter(r -> r.getDayOfWeek() == 4).findFirst().get();
		assertThat(thursday.isDayOff()).isFalse();
		assertThat(thursday.getStartTime().toString()).isEqualTo("19:00");
		assertThat(thursday.getEndTime().toString()).isEqualTo("04:00");
		assertThat(thursday.isEndNextDay()).isTrue();
	}

	@Test
	@DisplayName("Case 4: 슬래시(/)와 콤마(,)가 혼용된 복합 요일 지정 문자열을 정확히 매핑한다")
	void parse_complex_day_delimiters_test() {
		// given
		String rawText = "월 / 목, 금 / 토, 일 12:00 - 20:30 \n라스트오더 19:40\n매주 화, 수 정기휴무";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		assertThat(result).hasSize(7);

		// 화(2), 수(3) 휴무 확인
		assertThat(result.stream().filter(r -> r.getDayOfWeek() == 2 || r.getDayOfWeek() == 3)
			.allMatch(OperationTimeDto::isDayOff)).isTrue();

		// 영업일(1, 4, 5, 6, 7) 검증
		List<OperationTimeDto> workingDays = result.stream().filter(r -> !r.isDayOff()).toList();
		assertThat(workingDays).hasSize(5);
		assertThat(workingDays).allSatisfy(day -> {
			assertThat(day.getStartTime().toString()).isEqualTo("12:00");
			assertThat(day.getEndTime().toString()).isEqualTo("20:30");
			assertThat(day.getLastOrderTime().toString()).isEqualTo("19:40");
		});
	}

	@Test
	@DisplayName("Case 5: 시간으로 파싱할 수 없는 비정형 텍스트는 description 컬럼으로 이관된다")
	void parse_unstructured_text_to_description_test() {
		// given
		String rawText = "10:00 - 17:00 \n매주 수, 목 정기휴무 \n품절 시 조기마감";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		assertThat(result).hasSize(7);

		// 모든 요일에 description이 정상 매핑되었는지 확인
		assertThat(result).allSatisfy(day ->
			assertThat(day.getDescription()).isEqualTo("품절 시 조기마감")
		);

		// 수(3), 목(4) 휴무 확인
		assertThat(result.stream().filter(r -> r.getDayOfWeek() == 3 || r.getDayOfWeek() == 4)
			.allMatch(OperationTimeDto::isDayOff)).isTrue();
	}

	@Test
	@DisplayName("Case 7: 요일별 서로 다른 영업/브레이크 타임과 다중 라스트 오더를 개별적으로 정확히 파싱한다")
	void parse_highly_complex_weekly_schedule_test() {
		// given
		String rawText = "월 - 금 11:30 - 21:00 \n브레이크타임 15:00 - 17:00 \n라스트오더 14:30, 20:30\n" +
			"토 11:30 - 20:00\n라스트오더 14:30, 19:30\n매주 일 정기휴무";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		// 월~금 (5 * 2) + 토 (1) + 일 (1) = 총 12개 슬롯
		assertThat(result).hasSize(12);

		// 일요일(7) 휴무 확인
		assertThat(result.stream().filter(r -> r.getDayOfWeek() == 7).findFirst().get().isDayOff()).isTrue();

		// 금요일(5) 검증: 평일 다중 라스트 오더 분리 적용 확인
		List<OperationTimeDto> fridaySlots = result.stream()
			.filter(r -> r.getDayOfWeek() == 5)
			.sorted(Comparator.comparing(OperationTimeDto::getStartTime))
			.toList();

		assertThat(fridaySlots).hasSize(2);
		assertThat(fridaySlots.get(0).getStartTime().toString()).isEqualTo("11:30");
		assertThat(fridaySlots.get(0).getEndTime().toString()).isEqualTo("15:00");
		assertThat(fridaySlots.get(0).getLastOrderTime().toString()).isEqualTo("14:30"); // 첫 번째 라스트 오더

		assertThat(fridaySlots.get(1).getStartTime().toString()).isEqualTo("17:00");
		assertThat(fridaySlots.get(1).getEndTime().toString()).isEqualTo("21:00");
		assertThat(fridaySlots.get(1).getLastOrderTime().toString()).isEqualTo("20:30"); // 두 번째 라스트 오더

		// 토요일(6) 검증: 단일 타임슬롯 파싱 및 명세에 따른 단일(마지막) 라스트 오더 적용 확인
		List<OperationTimeDto> saturdaySlots = result.stream()
			.filter(r -> r.getDayOfWeek() == 6).toList();

		assertThat(saturdaySlots).hasSize(1);
		assertThat(saturdaySlots.get(0).getStartTime().toString()).isEqualTo("11:30");
		assertThat(saturdaySlots.get(0).getEndTime().toString()).isEqualTo("20:00");
		assertThat(saturdaySlots.get(0).getLastOrderTime().toString()).isEqualTo("19:30"); // 명세서 기준 19:30 매핑
	}

	// ===== 라스트오더 경계 규칙: 시작·마감 시각과 같은 라스트오더는 저장하지 않는다 =====

	@Test
	@DisplayName("익일 종료 슬롯(18:00~02:00)에 자정 전 라스트오더(23:00)가 저장된다")
	void parse_overnight_slot_evening_last_order_test() {
		// given
		String rawText = "매일 18:00 - 02:00 \n라스트오더 23:00";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		assertThat(result).hasSize(7);
		assertThat(result).extracting(OperationTimeDto::getDayOfWeek).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7);
		assertThat(result).allSatisfy(slot -> {
			assertThat(slot.getStartTime()).isEqualTo(LocalTime.of(18, 0));
			assertThat(slot.getEndTime()).isEqualTo(LocalTime.of(2, 0));
			assertThat(slot.isEndNextDay()).isTrue();
			assertThat(slot.getLastOrderTime()).isEqualTo(LocalTime.of(23, 0));
		});
	}

	@Test
	@DisplayName("익일 종료 슬롯(18:00~02:00)에 자정 후 라스트오더(01:00)가 저장된다")
	void parse_overnight_slot_dawn_last_order_test() {
		// given
		String rawText = "매일 18:00 - 02:00 \n라스트오더 01:00";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		assertThat(result).hasSize(7);
		assertThat(result).allSatisfy(slot ->
			assertThat(slot.getLastOrderTime()).isEqualTo(LocalTime.of(1, 0))
		);
	}

	@Test
	@DisplayName("익일 종료 슬롯에서 라스트오더가 영업 시작 시각(18:00)과 같으면 저장하지 않는다")
	void parse_overnight_slot_last_order_equals_start_test() {
		// given
		String rawText = "매일 18:00 - 02:00 \n라스트오더 18:00";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		assertThat(result).hasSize(7);
		assertThat(result).allSatisfy(slot ->
			assertThat(slot.getLastOrderTime()).isNull()
		);
	}

	@Test
	@DisplayName("익일 종료 슬롯에서 라스트오더가 마감 시각(02:00)과 같으면 저장하지 않는다")
	void parse_overnight_slot_last_order_equals_end_test() {
		// given
		String rawText = "매일 18:00 - 02:00 \n라스트오더 02:00";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		assertThat(result).hasSize(7);
		assertThat(result).allSatisfy(slot ->
			assertThat(slot.getLastOrderTime()).isNull()
		);
	}

	@Test
	@DisplayName("익일 종료 슬롯에서 영업하지 않는 시간(02:00~18:00 사이)의 라스트오더는 저장하지 않는다")
	void parse_overnight_slot_last_order_outside_operation_test() {
		// given
		List<String> rawTexts = List.of(
			"매일 18:00 - 02:00 \n라스트오더 17:00",
			"매일 18:00 - 02:00 \n라스트오더 03:00",
			"매일 18:00 - 02:00 \n라스트오더 14:00"
		);

		for (String rawText : rawTexts) {
			// when
			List<OperationTimeDto> result = parser.parse(rawText);

			// then
			assertThat(result).as(rawText).hasSize(7);
			assertThat(result).as(rawText).allSatisfy(slot ->
				assertThat(slot.getLastOrderTime()).isNull()
			);
		}
	}

	@Test
	@DisplayName("일반 슬롯에서 라스트오더가 영업 시작 시각(11:00)과 같으면 저장하지 않는다")
	void parse_normal_slot_last_order_equals_start_test() {
		// given
		String rawText = "매일 11:00 - 20:00 \n라스트오더 11:00";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		assertThat(result).hasSize(7);
		assertThat(result).allSatisfy(slot -> {
			assertThat(slot.isEndNextDay()).isFalse();
			assertThat(slot.getLastOrderTime()).isNull();
		});
	}

	@Test
	@DisplayName("일반 슬롯에서 라스트오더가 마감 시각(19:00)과 같으면 저장하지 않는다 (V2 45번 원문)")
	void parse_normal_slot_last_order_equals_end_test() {
		// given
		String rawText = "11:00 -19:00 \n라스트오더 19:00 \n매주 월, 화 정기휴무";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		assertThat(result).hasSize(7);

		// 월(1), 화(2) 휴무
		assertThat(result.stream().filter(r -> r.getDayOfWeek() == 1 || r.getDayOfWeek() == 2).toList())
			.hasSize(2)
			.allMatch(OperationTimeDto::isDayOff);

		// 수~일(3~7) 영업, 라스트오더 없음
		List<OperationTimeDto> workingDays = result.stream().filter(r -> r.getDayOfWeek() >= 3).toList();
		assertThat(workingDays).hasSize(5);
		assertThat(workingDays).allSatisfy(slot -> {
			assertThat(slot.isDayOff()).isFalse();
			assertThat(slot.getStartTime()).isEqualTo(LocalTime.of(11, 0));
			assertThat(slot.getEndTime()).isEqualTo(LocalTime.of(19, 0));
			assertThat(slot.getLastOrderTime()).isNull();
		});
	}

	@Test
	@DisplayName("일반 슬롯에서 시작·마감 바로 안쪽(11:01, 19:59)의 라스트오더는 저장된다")
	void parse_normal_slot_last_order_just_inside_boundary_test() {
		// given
		String afterStartText = "매일 11:00 - 20:00 \n라스트오더 11:01";
		String beforeEndText = "매일 11:00 - 20:00 \n라스트오더 19:59";

		// when
		List<OperationTimeDto> afterStartResult = parser.parse(afterStartText);
		List<OperationTimeDto> beforeEndResult = parser.parse(beforeEndText);

		// then
		assertThat(afterStartResult).hasSize(7);
		assertThat(afterStartResult).allSatisfy(slot ->
			assertThat(slot.getLastOrderTime()).isEqualTo(LocalTime.of(11, 1))
		);
		assertThat(beforeEndResult).hasSize(7);
		assertThat(beforeEndResult).allSatisfy(slot ->
			assertThat(slot.getLastOrderTime()).isEqualTo(LocalTime.of(19, 59))
		);
	}

	@Test
	@DisplayName("24:00 종료 슬롯(11:00~00:00)에 저녁 라스트오더(22:30)가 저장된다 (V2 123번 원문)")
	void parse_midnight_end_slot_evening_last_order_test() {
		// given
		String rawText = "매일 11:00 - 24:00 \n라스트오더 22:30";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		assertThat(result).hasSize(7);
		assertThat(result).extracting(OperationTimeDto::getDayOfWeek).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7);
		assertThat(result).allSatisfy(slot -> {
			assertThat(slot.getStartTime()).isEqualTo(LocalTime.of(11, 0));
			assertThat(slot.getEndTime()).isEqualTo(LocalTime.of(0, 0));
			assertThat(slot.isEndNextDay()).isTrue();
			assertThat(slot.getLastOrderTime()).isEqualTo(LocalTime.of(22, 30));
		});
	}

	@Test
	@DisplayName("33번 원문: 브레이크 14:20, 저녁 슬롯에만 라스트오더 20:30, 수요일 휴무로 13개 슬롯을 만든다")
	void parse_place_33_fixed_raw_text_test() {
		// given
		String rawText = "매일 11:00 - 21:00 \n브레이크 타임 14:20 - 17:00 \n라스트오더 20:30 \n매주 수 정기휴무";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		assertThat(result)
			.extracting(OperationTimeDto::getDayOfWeek, OperationTimeDto::isDayOff, OperationTimeDto::getStartTime,
				OperationTimeDto::getEndTime, OperationTimeDto::isEndNextDay, OperationTimeDto::getLastOrderTime,
				OperationTimeDto::getDescription)
			.containsExactlyInAnyOrder(
				Tuple.tuple(1, false, LocalTime.of(11, 0), LocalTime.of(14, 20), false, null, null),
				Tuple.tuple(1, false, LocalTime.of(17, 0), LocalTime.of(21, 0), false, LocalTime.of(20, 30), null),
				Tuple.tuple(2, false, LocalTime.of(11, 0), LocalTime.of(14, 20), false, null, null),
				Tuple.tuple(2, false, LocalTime.of(17, 0), LocalTime.of(21, 0), false, LocalTime.of(20, 30), null),
				Tuple.tuple(3, true, null, null, false, null, null),
				Tuple.tuple(4, false, LocalTime.of(11, 0), LocalTime.of(14, 20), false, null, null),
				Tuple.tuple(4, false, LocalTime.of(17, 0), LocalTime.of(21, 0), false, LocalTime.of(20, 30), null),
				Tuple.tuple(5, false, LocalTime.of(11, 0), LocalTime.of(14, 20), false, null, null),
				Tuple.tuple(5, false, LocalTime.of(17, 0), LocalTime.of(21, 0), false, LocalTime.of(20, 30), null),
				Tuple.tuple(6, false, LocalTime.of(11, 0), LocalTime.of(14, 20), false, null, null),
				Tuple.tuple(6, false, LocalTime.of(17, 0), LocalTime.of(21, 0), false, LocalTime.of(20, 30), null),
				Tuple.tuple(7, false, LocalTime.of(11, 0), LocalTime.of(14, 20), false, null, null),
				Tuple.tuple(7, false, LocalTime.of(17, 0), LocalTime.of(21, 0), false, LocalTime.of(20, 30), null)
			);
	}

	@Test
	@DisplayName("230번 원문: 수~금 11:30~16:00(LO 15:30), 토·일 브레이크 두 슬롯(저녁 LO 20:00), 월·화 휴무로 9개 슬롯을 만든다")
	void parse_place_230_fixed_raw_text_test() {
		// given
		String rawText = "수 - 금 11:30 - 16:00 \n라스트오더 15:30 \n토, 일 11:30 - 20:30 \n토, 일 브레이크타임 14:30 - 17:30 \n라스트오더 20:00 \n매주 월, 화 정기휴무";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		assertThat(result)
			.extracting(OperationTimeDto::getDayOfWeek, OperationTimeDto::isDayOff, OperationTimeDto::getStartTime,
				OperationTimeDto::getEndTime, OperationTimeDto::isEndNextDay, OperationTimeDto::getLastOrderTime,
				OperationTimeDto::getDescription)
			.containsExactlyInAnyOrder(
				Tuple.tuple(1, true, null, null, false, null, null),
				Tuple.tuple(2, true, null, null, false, null, null),
				Tuple.tuple(3, false, LocalTime.of(11, 30), LocalTime.of(16, 0), false, LocalTime.of(15, 30), null),
				Tuple.tuple(4, false, LocalTime.of(11, 30), LocalTime.of(16, 0), false, LocalTime.of(15, 30), null),
				Tuple.tuple(5, false, LocalTime.of(11, 30), LocalTime.of(16, 0), false, LocalTime.of(15, 30), null),
				Tuple.tuple(6, false, LocalTime.of(11, 30), LocalTime.of(14, 30), false, null, null),
				Tuple.tuple(6, false, LocalTime.of(17, 30), LocalTime.of(20, 30), false, LocalTime.of(20, 0), null),
				Tuple.tuple(7, false, LocalTime.of(11, 30), LocalTime.of(14, 30), false, null, null),
				Tuple.tuple(7, false, LocalTime.of(17, 30), LocalTime.of(20, 30), false, LocalTime.of(20, 0), null)
			);
	}

	@Test
	@DisplayName("183번 원문: 매일 11:00~22:00, 라스트오더 21:30이 last_order_time으로 저장되고 description은 비어 있다")
	void parse_place_183_fixed_raw_text_test() {
		// given
		String rawText = "매일 11:00 - 22:00 \n라스트오더 21:30";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		assertThat(result)
			.extracting(OperationTimeDto::getDayOfWeek, OperationTimeDto::isDayOff, OperationTimeDto::getStartTime,
				OperationTimeDto::getEndTime, OperationTimeDto::isEndNextDay, OperationTimeDto::getLastOrderTime,
				OperationTimeDto::getDescription)
			.containsExactlyInAnyOrder(
				Tuple.tuple(1, false, LocalTime.of(11, 0), LocalTime.of(22, 0), false, LocalTime.of(21, 30), null),
				Tuple.tuple(2, false, LocalTime.of(11, 0), LocalTime.of(22, 0), false, LocalTime.of(21, 30), null),
				Tuple.tuple(3, false, LocalTime.of(11, 0), LocalTime.of(22, 0), false, LocalTime.of(21, 30), null),
				Tuple.tuple(4, false, LocalTime.of(11, 0), LocalTime.of(22, 0), false, LocalTime.of(21, 30), null),
				Tuple.tuple(5, false, LocalTime.of(11, 0), LocalTime.of(22, 0), false, LocalTime.of(21, 30), null),
				Tuple.tuple(6, false, LocalTime.of(11, 0), LocalTime.of(22, 0), false, LocalTime.of(21, 30), null),
				Tuple.tuple(7, false, LocalTime.of(11, 0), LocalTime.of(22, 0), false, LocalTime.of(21, 30), null)
			);
	}

	@Test
	@DisplayName("136번 원문: 월~금 브레이크 두 슬롯, 토·일 13:00~20:00으로 12개 슬롯을 만든다")
	void parse_place_136_fixed_raw_text_test() {
		// given
		String rawText = "월 - 금 13:00 - 21:00 \n브레이크타임 17:00 - 18:00 \n토, 일 13:00 - 20:00";

		// when
		List<OperationTimeDto> result = parser.parse(rawText);

		// then
		assertThat(result)
			.extracting(OperationTimeDto::getDayOfWeek, OperationTimeDto::isDayOff, OperationTimeDto::getStartTime,
				OperationTimeDto::getEndTime, OperationTimeDto::isEndNextDay, OperationTimeDto::getLastOrderTime,
				OperationTimeDto::getDescription)
			.containsExactlyInAnyOrder(
				Tuple.tuple(1, false, LocalTime.of(13, 0), LocalTime.of(17, 0), false, null, null),
				Tuple.tuple(1, false, LocalTime.of(18, 0), LocalTime.of(21, 0), false, null, null),
				Tuple.tuple(2, false, LocalTime.of(13, 0), LocalTime.of(17, 0), false, null, null),
				Tuple.tuple(2, false, LocalTime.of(18, 0), LocalTime.of(21, 0), false, null, null),
				Tuple.tuple(3, false, LocalTime.of(13, 0), LocalTime.of(17, 0), false, null, null),
				Tuple.tuple(3, false, LocalTime.of(18, 0), LocalTime.of(21, 0), false, null, null),
				Tuple.tuple(4, false, LocalTime.of(13, 0), LocalTime.of(17, 0), false, null, null),
				Tuple.tuple(4, false, LocalTime.of(18, 0), LocalTime.of(21, 0), false, null, null),
				Tuple.tuple(5, false, LocalTime.of(13, 0), LocalTime.of(17, 0), false, null, null),
				Tuple.tuple(5, false, LocalTime.of(18, 0), LocalTime.of(21, 0), false, null, null),
				Tuple.tuple(6, false, LocalTime.of(13, 0), LocalTime.of(20, 0), false, null, null),
				Tuple.tuple(7, false, LocalTime.of(13, 0), LocalTime.of(20, 0), false, null, null)
			);
	}
}
