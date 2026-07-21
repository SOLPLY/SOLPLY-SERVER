package org.sopt.solply_server.domain.place.dto;

import java.time.LocalTime;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OperationTimeDto {

	private int dayOfWeek; // 1(월) ~ 7(일)

	private boolean isDayOff;

	private LocalTime startTime;

	@Setter
	private LocalTime endTime;

	@Setter
	private boolean endNextDay;

	@Setter
	private LocalTime lastOrderTime;

	@Setter
	private String description;

	public static OperationTimeDto createDayOn(int dayOfWeek, LocalTime startTime, LocalTime endTime) {

		OperationTimeDto s = new OperationTimeDto();

		s.dayOfWeek = dayOfWeek;
		s.isDayOff = false;
		s.startTime = startTime;
		s.endTime = endTime;
		s.endNextDay = !startTime.isBefore(endTime);

		return s;
	}

	public static OperationTimeDto createDayOff(int dayOfWeek) {
		OperationTimeDto s = new OperationTimeDto();

		s.dayOfWeek = dayOfWeek;
		s.isDayOff = true;
		return s;
	}

}
