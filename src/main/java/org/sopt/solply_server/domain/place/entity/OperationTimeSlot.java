package org.sopt.solply_server.domain.place.entity;

import java.time.LocalTime;

import org.sopt.solply_server.global.entity.DayOfWeek;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@ToString
@Table(name = "operation_time_slots")
public class OperationTimeSlot {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "place_id")
	private Place place;

	@Column(nullable = false, columnDefinition = "TINYINT")
	private DayOfWeek dayOfWeek; // 1(월) ~ 7(일)

	@Column(nullable = false)
	private boolean isDayOff;

	@Column
	private LocalTime startTime;

	@Setter
	@Column
	private LocalTime endTime;

	@Setter
	@Column
	private Boolean endNextDay;

	@Setter
	@Column
	private LocalTime lastOrderTime;

	@Setter
	@Column
	private String description;

	public static OperationTimeSlot createDayOn(DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {

		OperationTimeSlot s = new OperationTimeSlot();

		s.dayOfWeek = dayOfWeek;
		s.isDayOff = false;
		s.startTime = startTime;
		s.endTime = endTime;

		return s;
	}

	public static OperationTimeSlot createDayOff(DayOfWeek dayOfWeek) {
		OperationTimeSlot s = new OperationTimeSlot();

		s.dayOfWeek = dayOfWeek;
		s.isDayOff = true;
		return s;
	}

}
