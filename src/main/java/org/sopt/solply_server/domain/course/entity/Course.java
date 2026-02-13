package org.sopt.solply_server.domain.course.entity;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

import lombok.*;

import org.sopt.solply_server.domain.course.dto.PlaceInCourseInfo;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.tag.entity.Tag;
import org.sopt.solply_server.domain.town.entity.Town;
import org.sopt.solply_server.domain.user.entity.User;
import org.sopt.solply_server.global.entity.BaseTimeEntity;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "courses",
	indexes = {
		@Index(name = "idx_course_town_id", columnList = "town_id"),
		@Index(name = "idx_course_is_shared", columnList = "is_shared"),
		@Index(name = "idx_course_created_by", columnList = "created_by")
	}
)
public class Course extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String introduction;

	@Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT false")
	private boolean isShared;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "town_id", nullable = false)
	private Town town;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by")
	private User createdBy;

	@Column(nullable = false)
	private boolean active;

	@OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("placeOrder ASC")
	private List<CoursePlace> coursePlaces = new ArrayList<>();

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_tag_id")
	private Tag tag;

	public static Course create(String name, String introduction, Town town, User createdBy, Boolean active, Tag tag) {
		return Course.builder()
			.name(name)
			.introduction(introduction)
			.isShared(false)
			.town(town)
			.createdBy(createdBy)
			.active(active)
			.tag(tag)
			.coursePlaces(new ArrayList<>())
			.build();
	}

	public void addCoursePlace(CoursePlace coursePlace) {
		this.coursePlaces.add(coursePlace);
		coursePlace.assignCourse(this);
	}

	public boolean isCreatedBy(Long userId) {
		if (this.createdBy == null || userId == null) {
			return false;
		}
		return this.createdBy.getId().equals(userId);
	}

	public void updateName(String newName) {
		this.name = newName;
	}

	public void updateIntroduction(String newIntroduction) {
		this.introduction = newIntroduction;
	}

	public List<PlaceInCourseInfo> getPlacesInCourseInfo() {
		return this.coursePlaces.stream()
			.map(coursePlace -> PlaceInCourseInfo.of(
				coursePlace.getPlace().getId(),
				coursePlace.getPlaceOrder()
			))
			.toList();
	}

	/**
	 * 코스에 포함된 Place 엔티티들만 반환
	 */
	public List<Place> getPlaces() {
		return this.coursePlaces.stream()
			.map(CoursePlace::getPlace)
			.toList();
	}

	public int getPlaceCount() {
		return this.coursePlaces.size();
	}

	public void updateCourseTag(Tag courseTag) {
		this.tag = courseTag;
	}

	public void updateCourseIntro(String intro) {
		this.introduction = intro;
	}

	public void updateActivation(boolean active) {
		this.active = active;
	}
}