package org.sopt.solply_server.global.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class DayOfWeekConverter implements AttributeConverter<DayOfWeek,Integer> {
	@Override
	public Integer convertToDatabaseColumn(DayOfWeek dayOfWeek) {
		return dayOfWeek.getValue();
	}

	@Override
	public DayOfWeek convertToEntityAttribute(Integer integer) {
		return DayOfWeek.from(integer);
	}
}
