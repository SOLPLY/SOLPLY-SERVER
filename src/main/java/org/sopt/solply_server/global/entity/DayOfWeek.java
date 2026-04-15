package org.sopt.solply_server.global.entity;

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
		throw new IllegalArgumentException("Invalid Day: " + value);
	}

	public int getValue() {
		return value;
	}
}
