package org.sopt.solply_server.domain.operation.util;

import static org.assertj.core.api.AssertionsForInterfaceTypes.*;

import java.util.Comparator;
import java.util.List;

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
}
