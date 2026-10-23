package org.sopt.solply_server.global.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class DayOfWeekConverter implements AttributeConverter<DayOfWeek, Integer> {
	@Override
	public Integer convertToDatabaseColumn(DayOfWeek dayOfWeek) {
		if (dayOfWeek == null) {
			return null;
		}
		return dayOfWeek.getValue();
	}

	@Override
	public DayOfWeek convertToEntityAttribute(Integer integer) {
		if (integer == null) {
			return null;
		}
		return DayOfWeek.from(integer);
	}
}
