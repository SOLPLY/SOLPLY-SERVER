package org.sopt.solply_server.domain.place.util;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.sopt.solply_server.domain.place.dto.OperationTimeDto;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OperationHourParser {

	private static final Map<String, Integer> DAY_MAP = Map.of(
		"월", 1, "화", 2, "수", 3, "목", 4, "금", 5, "토", 6, "일", 7
	);

	public List<OperationTimeDto> parse(String rawText) {
		List<OperationTimeDto> result = new ArrayList<>();
		String[] lines = rawText.split("\n");
		List<Integer> lastActiveDays = new ArrayList<>();
		Set<Integer> processedDays = new HashSet<>();

		if (lines.length == 1 && lines[0].contains("연중무휴")) {
			for (Integer day : DAY_MAP.values()) {
				OperationTimeDto dto = OperationTimeDto.createDayOn(day, LocalTime.MIN, LocalTime.MIN);
				dto.setDescription("연중무휴");
				result.add(dto);
			}
		}

		for (String line : lines) {
			line = line.trim();
			if (line.isEmpty())
				continue;

			// 1. 휴무 처리
			if (line.contains("정기휴무")) {
				List<Integer> holidays = parseDays(line);
				holidays.forEach(day -> {
					processedDays.add(day);
					result.removeIf(dto -> dto.getDayOfWeek() == day);
					result.add(OperationTimeDto.createDayOff(day));
				});
				continue;
			}

			// 2. 브레이크 타임 (기존 슬롯 쪼개기)
			if (line.contains("브레이크타임") || line.contains("브레이크 타임")) {
				List<LocalTime> bTimes = extractTimes(line);
				if (bTimes.size() >= 2) {
					splitExistingSlots(result, lastActiveDays, bTimes.get(0), bTimes.get(1));
				}
				continue;
			}

			// 3. 라스트 오더
			if (line.contains("라스트오더")) {
				if (line.split("/").length > 1) {
					parseOperationTime(line.split("/")[0], result, lastActiveDays, processedDays);
				}
				List<LocalTime> loTimes = extractTimes(line);
				applyLastOrder(result, lastActiveDays, loTimes);
				continue;
			}

			// 4. 기본 영업시간 파싱
			parseOperationTime(line, result, lastActiveDays, processedDays);
			// List<Integer> days = parseDays(line);
			// List<LocalTime> times = extractTimes(line);
			// if (times.size() == 2) {
			// 	if (days.isEmpty() && !processedDays.isEmpty())
			// 		throw new IllegalStateException("처리된 요일이 있는데, 요일 없는 시간데이터가 들어옴");
			//
			// 	days = days.isEmpty() ? List.of(1, 2, 3, 4, 5, 6, 7) : days;
			// 	for (Integer day : days) {
			// 		result.add(OperationTimeDto.createDayOn(day, times.get(0), times.get(1)));
			// 	}
			// 	lastActiveDays = days;
			// }
		}
		return result;
	}

	private void parseOperationTime(String line, List<OperationTimeDto> result, List<Integer> lastActiveDays,
		Set<Integer> processedDays) {
		// 4. 기본 영업시간 파싱
		List<Integer> days = parseDays(line);
		List<LocalTime> times = extractTimes(line);
		if (times.size() == 2) {
			if (days.isEmpty() && !processedDays.isEmpty())
				throw new IllegalStateException("처리된 요일이 있는데, 요일 없는 시간데이터가 들어옴");

			days = days.isEmpty() ? List.of(1, 2, 3, 4, 5, 6, 7) : days;
			for (Integer day : days) {
				result.add(OperationTimeDto.createDayOn(day, times.get(0), times.get(1)));
			}
			lastActiveDays.clear();
			lastActiveDays.addAll(days);
		}
	}

	private void splitExistingSlots(List<OperationTimeDto> result, List<Integer> days, LocalTime bStart,
		LocalTime bEnd) {
		List<OperationTimeDto> newSlots = new ArrayList<>();
		Iterator<OperationTimeDto> it = result.iterator();
		while (it.hasNext()) {
			OperationTimeDto slot = it.next();
			if (days.contains(slot.getDayOfWeek()) && !slot.isDayOff()) {
				LocalTime originalEnd = slot.getEndTime();
				slot.setEndTime(bStart); // 오전 슬롯으로 변경
				newSlots.add(OperationTimeDto.createDayOn(slot.getDayOfWeek(), bEnd, originalEnd)); // 오후 슬롯 추가
			}
		}
		result.addAll(newSlots);
	}

	private void applyLastOrder(List<OperationTimeDto> result, List<Integer> days, List<LocalTime> loTimes) {
		for (Integer day : days) {
			result.stream()
				.filter(s -> s.getDayOfWeek() == day && !s.isDayOff())
				.forEach(slot -> {
					for (LocalTime lo : loTimes) {
						if (lo.isAfter(slot.getStartTime()) && lo.isBefore(slot.getEndTime())) {
							slot.setLastOrderTime(lo);
						}
					}
				});
		}
	}

	private List<Integer> parseDays(String line) {
		if (line.contains("매일"))
			return List.of(1, 2, 3, 4, 5, 6, 7);

		else if (line.contains("평일"))
			return List.of(1, 2, 3, 4, 5);

		else if (line.contains("주말"))
			return List.of(6, 7);

		Set<Integer> days = new HashSet<>();
		String cleanLine = line.replaceAll("(브레이크타임|브레이크 타임|라스트오더|정기휴무|매주|영업시간)", "").trim();
		cleanLine = cleanLine.replaceAll("\\d{1,2}:\\d{2}\\s*-\\s*\\d{1,2}:\\d{2}", "");
		String[] parts = cleanLine.split("[/,]+");
		log.info("cleanLine:{}", cleanLine);
		for (String part : parts) {
			part = part.trim();
			if (part.isEmpty())
				continue;

			if (part.contains("-")) {
				Pattern p = Pattern.compile("([월화수목금토일])\\s*-\\s*([월화수목금토일])");
				Matcher m = p.matcher(part);
				if (m.find()) {
					int start = DAY_MAP.get(m.group(1));
					int end = DAY_MAP.get(m.group(2));

					for (int i = start; i <= end; i++)
						days.add(i);
				}
			} else {
				for (String d : DAY_MAP.keySet()) {
					if (part.contains(d))
						days.add(DAY_MAP.get(d));
				}
			}
		}
		return days.stream().sorted().collect(Collectors.toList());
	}

	private List<LocalTime> extractTimes(String line) {
		List<LocalTime> times = new ArrayList<>();
		Matcher m = Pattern.compile("(\\d{1,2}:\\d{2})").matcher(line);
		while (m.find()) {
			String timeStr = m.group(1);
			// 24:00으로 들어오면 00:00으로 강제 변환
			if (timeStr.startsWith("24:")) {
				timeStr = "00:00";
			}
			times.add(LocalTime.parse(timeStr.length() == 4 ? "0" + timeStr : timeStr));
		}

		// if (times.size() != 2) {
		// 	String message =
		// 		String.format("시간 추출 실패! [times.size(): %d] 원문:[%s] - 시간 데이터 오류입니다.", times.size(), line);
		// 	throw new IllegalStateException(message);
		// }
		return times;
	}
}
