package org.sopt.solply_server.global.entity;

import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;

public enum DayOfWeek {
	MON(1), TUE(2), WED(3), THU(4), FRI(5), SAT(6), SUN(7);

	private final int value;

	DayOfWeek(int value) {
		this.value = value;
	}

	public static DayOfWeek from(int value) {
		for (DayOfWeek day : values()) {
			if (day.value==value)
				return day;
		}
		throw new BusinessException(ErrorCode.INVALID_DAY_VALUE);
	}

	public int getValue() {
		return value;
	}
}
