package org.sopt.solply_server.domain.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sopt.solply_server.domain.auth.entity.SocialPlatform;
import org.sopt.solply_server.domain.place.cache.SnapshotBox;
import org.sopt.solply_server.domain.place.cache.SnapshotInstaller;
import org.sopt.solply_server.domain.place.cache.SnapshotLoader;
import org.sopt.solply_server.domain.place.cache.SnapshotRebuilder;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotCursorPolicy;
import org.sopt.solply_server.domain.place.cache.metadata.SnapshotMetadataRepository;
import org.sopt.solply_server.domain.place.dto.PlacePreviewDto;
import org.sopt.solply_server.domain.place.dto.request.PlaceFilterGetRequest;
import org.sopt.solply_server.domain.place.dto.request.PlaceSortType;
import org.sopt.solply_server.domain.place.dto.response.PlaceFilterGetResponse;
import org.sopt.solply_server.domain.place.util.PlaceListCursor;
import org.sopt.solply_server.domain.user.entity.UserRole;
import org.sopt.solply_server.global.jwt.JwtTokenProvider;
import org.sopt.solply_server.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * <b>발행물이 이 인스턴스보다 앞서 있을 때 목록 요청이 무엇을 하는가</b> — 실제 MySQL 위에서,
 * 실제 시큐리티·서블릿 비동기 재디스패치·{@code GlobalExceptionHandler}를 전부 지나며 묻는다.
 *
 * <p><b>왜 목으로는 부족한가.</b> 여기서 겨누는 것이 전부 배선 자체다 — 회차가 실제 발행물 행에서
 * 오고, 커서가 그 행의 번호를 싣고 다니며, 응답이 비동기 재디스패치를 지나 JSON이 되고, 재개가
 * 다른 스레드에서 도는 동안에도 <b>누구의 북마크인지</b>가 유지돼야 한다. 목 배선에서는 이 중
 * 하나도 확인되지 않는다 — 판정 규칙 자체는 {@link PlaceListRequestOrchestratorTest}가 따로 문다.
 *
 * <p><b>"다른 인스턴스가 앞서 갔다"는 번호만 올려 만든다.</b> {@code SnapshotRebuilder#bump}가
 * 공유 번호만 올리므로 이 JVM의 힙은 옛 시점에 머문 채 DB만 앞서 간다 — 방금 뜬 인스턴스나
 * 리빌드가 늦은 인스턴스가 처한 상태 그대로다.
 *
 * <p><b>원본 읽기에 문을 달아 그 창을 붙든다.</b> 아무 장치 없이는 리빌드가 워낙 빨라 "뒤처진
 * 상태"가 유지되지 않는다. 그래서 {@link SnapshotLoader#readSourceState()}에 문을 달아 테스트가
 * 열어 줄 때까지 어떤 경로도 리빌드를 끝내지 못하게 한다 — 요청은 그 문 앞에서 <b>실제로
 * 기다린다.</b>
 *
 * <p><b>뒷정리는 커밋된 픽스처를 직접 지운다</b> — {@code @SpringBootTest}는 롤백하지 않는다
 * ({@code PlaceListFlowIT}과 같은 사정).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlaceListSnapshotCatchUpIT extends MySqlContainerSupport {

    /** 이름은 베이스의 {@code datasource}와 달라야 한다 — static이라 같으면 설정이 통째로 숨는다 */
    @DynamicPropertySource
    static void catchUpProps(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("solply.place-stats.count-cron", () -> "-");
        registry.add("solply.place-stats.bookmark-delta-cron", () -> "-");
        registry.add("solply.place-stats.count-safety-cron", () -> "-");
        registry.add("solply.place-stats.score-cron", () -> "-");
        registry.add("solply.auth.cleanup-cron", () -> "-");
        // 시간 초과를 실제로 재는 테스트가 있어 짧게 잡되, 문을 여는 즉시 끝나는 정상 경로가
        // 이 값에 쫓기지 않을 만큼은 남긴다
        registry.add("solply.place-list-snapshot.request-wait-timeout-ms",
                () -> String.valueOf(REQUEST_WAIT_TIMEOUT_MS));
        // ⚠️ 실패 백오프를 사실상 없앤다. 코디네이터는 컨텍스트당 하나라 <b>실패를 일부러 만드는
        //    테스트가 남긴 백오프가 다음 테스트로 넘어간다</b> — 기본 5초면 뒤이은 정상 경로가
        //    리빌드를 못 띄워 503으로 끊긴다(실측). 백오프 자체의 계약은
        //    SnapshotLoadCoordinatorTest가 시계를 손에 쥐고 따로 문다.
        registry.add("solply.place-list-snapshot.failure-backoff-ms", () -> "1");
    }

    private static final long REQUEST_WAIT_TIMEOUT_MS = 1_500L;
    private static final long AWAIT_SECONDS = 30L;

    private static final String PLACES_PATH = "/api/places";
    private static final String TOWN_NAME_PREFIX = "회차대기IT동네";
    private static final String USER_NICKNAME_PREFIX = "회차대기IT유저";
    private static final LocalDateTime BOOKMARKED_AT = LocalDateTime.of(2026, 9, 12, 2, 0, 0);
    private static final LocalDateTime PLACE_CREATED_AT = BOOKMARKED_AT.minusDays(1);

    @Autowired private MockMvc mockMvc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private PlaceListRequestOrchestrator orchestrator;
    @Autowired private SnapshotInstaller installer;
    @Autowired private SnapshotBox snapshotBox;

    /** 원본 읽기에 문을 달고, 그것이 <b>몇 번</b> 났는지도 함께 센다 */
    @SpyBean private SnapshotLoader loader;
    /** 요청마다 번호를 읽는지 세는 자리 */
    @SpyBean private SnapshotMetadataRepository metadataRepository;

    private SnapshotRebuilder rebuilder;

    /** 열려 있으면 {@code null}. 닫으면 원본 읽기가 여기서 멈춘다 */
    private final AtomicReference<CountDownLatch> downloadGate = new AtomicReference<>();

    private long townId;
    private long bookmarkedPlaceId;
    private long plainPlaceId;
    private long userId;
    private String accessToken;

    @BeforeEach
    void setUp() {
        reset(loader, metadataRepository);
        rebuilder = new SnapshotRebuilder(installer, metadataRepository, transactionManager);

        townId = createTown(TOWN_NAME_PREFIX + nextSeq());
        bookmarkedPlaceId = createPlace(townId, "회차대기북마크", PLACE_CREATED_AT);
        plainPlaceId = createPlace(townId, "회차대기일반", PLACE_CREATED_AT.plusMinutes(1));
        userId = createUser();
        insertBookmark(userId, bookmarkedPlaceId, BOOKMARKED_AT);
        accessToken = jwtTokenProvider.createAccessToken(
                userId, SocialPlatform.KAKAO, UserRole.USER, UUID.randomUUID().toString());

        // 이 인스턴스가 최신인 상태에서 시작한다 — 여기까지는 문이 열려 있어야 한다
        rebuilder.rebuildAndInstall();

        gateDownloads();
        clearInvocations(loader, metadataRepository);
    }

    /**
     * 문을 열고 <b>설치가 실제로 따라잡을 때까지</b> 기다린 뒤 끝낸다. 문이 닫힌 채로 테스트가
     * 끝나면 다음 테스트의 픽스처 설치가 그 문에 걸리고, 문 앞에 선 폴 스레드도 그대로 남는다.
     */
    @AfterEach
    void openGateAndSettle() {
        openDownloads();
        reset(loader, metadataRepository);    // 문도 예외도 걷어낸다
        Awaitility.await().atMost(Duration.ofSeconds(AWAIT_SECONDS))
                .until(() -> {
                    installer.rebuildAndInstall();
                    return installer.installedRevision() == currentRevisionOnOwnConnection();
                });
    }

    // === 뒤처지지 않은 보통 요청 ===

    /**
     * <b>보통 첫 페이지가 나가는 쿼리는 번호 조회 하나뿐이다.</b> 원본을 다시 읽는 일은 없다 —
     * 여기서 {@code readSourceState()}가 나면 요청마다 place_stats 전량을 읽게 된다.
     *
     * <p><b>같은 자리에서 인증 사용자의 북마크도 확인한다</b> — 비동기로 바뀐 응답 경로가
     * {@code userId}를 흘리면 여기가 {@code false}로 뒤집힌다.
     */
    @Test
    void 최신인_첫_페이지는_번호만_읽고_북마크는_그대로_실린다() throws Exception {
        JsonNode data = successData(call(firstPageRequest().header(
                "Authorization", "Bearer " + accessToken)));

        assertThat(idsOf(data)).contains(bookmarkedPlaceId, plainPlaceId);
        assertThat(bookmarkedFlagOf(data, bookmarkedPlaceId)).isTrue();
        assertThat(bookmarkedFlagOf(data, plainPlaceId)).isFalse();
        verify(metadataRepository, times(1)).read();
        verify(loader, never()).readSourceState();
    }

    /** 북마크 검색은 회차와 무관하다 — 번호를 보러 가는 쿼리 자체가 나가지 않는다 */
    @Test
    void 북마크_검색은_공유_번호를_읽지_않는다() throws Exception {
        JsonNode data = successData(call(listRequest(townId, true, "10", null)
                .header("Authorization", "Bearer " + accessToken)));

        assertThat(idsOf(data)).containsExactly(bookmarkedPlaceId);
        verify(metadataRepository, never()).read();
        verify(loader, never()).readSourceState();
    }

    /**
     * <b>회차가 맞는 커서도 번호를 한 번 읽는다.</b> 옛 구조에서는 여기를 건너뛰었는데, 그 지름길은
     * <b>이 인스턴스가 뒤처지고 커서도 그만큼 낡은</b> 경우를 통과시킨다 — 둘이 서로 맞으므로
     * 아무 문제 없어 보이는 채로 옛 회차를 최신이라 말한다. 대신 원본은 읽지 않는다.
     */
    @Test
    void 회차가_맞는_커서_요청도_번호를_한_번_읽는다() throws Exception {
        JsonNode page1 = successData(call(pageRequest(1, null)));
        String cursor = issuedCursor(page1);
        clearInvocations(loader, metadataRepository);

        JsonNode page2 = successData(call(pageRequest(1, cursor)));

        assertThat(idsOf(page2)).isNotEmpty().doesNotContainAnyElementsOf(idsOf(page1));
        verify(metadataRepository, times(1)).read();
        verify(loader, never()).readSourceState();
    }

    // === 뒤처진 인스턴스 ===

    /**
     * <b>공유 번호가 앞서 갔으면 기다렸다가 그것으로 답한다.</b> 기다리지 않으면 방금 등록된
     * 장소가 이 인스턴스에서만 한 폴 주기 동안 빠지고, 사용자에게는 "새로고침할 때마다 결과가
     * 달라지는" 모양으로 보인다.
     *
     * <p>문이 닫혀 있어 폴도 리빌드를 끝내지 못한다 — 응답에 새 장소가 실렸다면 그것은 <b>이 요청이
     * 기다려서 지은 것</b>이다.
     */
    @Test
    void 뒤처진_첫_페이지는_리빌드를_기다린_뒤에_답한다() throws Exception {
        long newPlaceId = createPlace(townId, "회차대기신규", PLACE_CREATED_AT.plusMinutes(2));
        rebuilder.bump(SnapshotCursorPolicy.ADVANCE);
        long sharedCursorVersion = rebuilder.current().cursorVersion();
        assertThat(installer.installedCursorVersion()).isLessThan(sharedCursorVersion);

        MvcResult started = startAsync(firstPageRequest());
        openDownloads();
        JsonNode data = successData(dispatch(started));

        assertThat(idsOf(data)).contains(newPlaceId);
        assertThat(installer.installedCursorVersion()).isEqualTo(sharedCursorVersion);
    }

    /**
     * <b>표시값만 바뀐 변경은 첫 페이지를 기다리게 하지 않는다.</b> 커서 회차가 그대로이므로 이
     * 인스턴스는 이미 "회차만큼은 최신"이고, 그 회차로 답하는 것이 맞다 — 반영은 폴이 맡는다.
     *
     * <p>이것이 번호를 둘로 가른 값어치다. 하나였다면 이름 한 칸 고친 수정마다 모든 첫 페이지가
     * 리빌드를 기다렸을 것이다.
     */
    @Test
    void 표시값만_바뀐_변경은_첫_페이지를_기다리게_하지_않는다() throws Exception {
        long cursorVersionBefore = snapshotBox.current().cursorVersion();
        String renamed = "회차대기개명" + nextSeq();
        jdbcTemplate.update("UPDATE places SET name = ? WHERE id = ?", renamed, plainPlaceId);
        jdbcTemplate.update("UPDATE place_stats SET name = ? WHERE place_id = ?",
                renamed, plainPlaceId);
        rebuilder.bump(SnapshotCursorPolicy.PRESERVE);

        // 문이 닫혀 있는데도 비동기로 넘어가지 않는다 — 기다릴 이유가 없기 때문이다
        JsonNode data = successData(call(firstPageRequest()));

        assertThat(idsOf(data)).contains(plainPlaceId);
        assertThat(snapshotBox.current().cursorVersion())
                .as("표시값만 바뀐 변경은 커서 회차를 올리지 않는다")
                .isEqualTo(cursorVersionBefore);
        verify(loader, never()).readSourceState();
    }

    /**
     * <b>다른 인스턴스가 발급한 커서를 뒤처진 인스턴스가 그대로 이어 준다.</b> 이 설계의 핵심
     * 시나리오다 — 커서가 가리키는 회차가 <b>공유 현재</b>이므로 만료가 아니고, 같은 커서로
     * 답해야 한다. 여기서 400을 내면 사용자의 스크롤이 어느 인스턴스에 붙었느냐에 따라 끊긴다.
     *
     * <p>커서의 좌표는 이 인스턴스가 옛 회차에서 발급한 것을 그대로 쓰고 회차 번호만 새 발행물의
     * 것으로 바꾼다 — 두 발행물의 원본이 같으므로 그것이 곧 "다른 인스턴스가 발급했을 커서"다.
     */
    @Test
    void 공유_현재_회차의_커서는_리빌드를_기다렸다가_같은_커서로_이어_준다() throws Exception {
        JsonNode page1 = successData(call(pageRequest(1, null)));
        List<Long> firstIds = idsOf(page1);
        rebuilder.bump(SnapshotCursorPolicy.ADVANCE);
        long sharedCursorVersion = rebuilder.current().cursorVersion();
        String cursorFromOtherInstance =
                reversioned(issuedCursor(page1), sharedCursorVersion);

        MvcResult started = startAsync(pageRequest(1, cursorFromOtherInstance));
        openDownloads();
        JsonNode page2 = successData(dispatch(started));

        assertThat(idsOf(page2)).isNotEmpty().doesNotContainAnyElementsOf(firstIds);
        assertThat(installer.installedCursorVersion()).isEqualTo(sharedCursorVersion);
    }

    /**
     * <b>진짜로 지난 회차의 커서는 그대로 만료(400)다.</b> 공유 현재가 이미 다른 회차라 기다려도
     * 오지 않는다 — 503으로 부르면 되지도 않을 재시도를 시키게 된다.
     */
    @Test
    void 지난_회차의_커서는_만료_400이다() throws Exception {
        JsonNode page1 = successData(call(pageRequest(1, null)));
        String staleCursor = issuedCursor(page1);
        openDownloads();
        rebuilder.rebuildAndInstall();      // 회차가 한 번 더 돌았고 이 인스턴스도 따라갔다

        assertError(call(pageRequest(1, staleCursor)), 400, "PLACE-006");
    }

    // === 입력 검증이 먼저다 ===

    /** 망가진 토큰은 코덱에서 끊긴다 — 그 요청으로 리빌드가 유발되지 않는 것까지가 계약이다 */
    @Test
    void 망가진_커서_토큰은_400이고_리빌드를_유발하지_않는다() throws Exception {
        assertError(call(pageRequest(10, "이건커서가아니다")), 400, "PLACE-003");

        verify(loader, never()).readSourceState();
    }

    /**
     * <b>파라미터 검증이 커서 해석보다 앞선다.</b> 바인딩 단계에서 끊기므로 컨트롤러에 닿지도
     * 않는다 — 커서 오류로 답하면 클라이언트가 고쳐야 할 곳을 잘못 짚는다.
     */
    @Test
    void 잘못된_size는_커서_오류보다_먼저_400이_된다() throws Exception {
        assertError(call(listRequest(townId, false, "0", "이건커서가아니다")), 400, "COMMON-001");

        verify(metadataRepository, never()).read();
    }

    /** 동네가 없으면 원래 경로에서 그대로 끊긴다 — 뒤처짐 판정은 검증을 통과한 요청에만 붙는다 */
    @Test
    void 없는_동네_요청은_리빌드를_유발하지_않는다() throws Exception {
        int status = call(listRequest(99_999_999L, false, "10", null))
                .getResponse().getStatus();

        assertThat(status).isBetween(400, 499);
        verify(loader, never()).readSourceState();
    }

    // === 따라잡지 못했을 때 ===

    /**
     * <b>리빌드가 실패해도 낡은 회차로 조용히 답하지 않는다 — 503이다.</b> 클라이언트는 목록을
     * 그대로 두고 같은 요청을 다시 보내면 된다. 그것이 만료(400)와 갈라 둔 이유다.
     *
     * <p><b>실패가 요청을 곧바로 끊지는 않는다.</b> 요청의 계약은 "자기 예산 안에서 복구를
     * 기다린다"이므로, 한 번의 실패는 대기표를 깨우지 않고 백오프 뒤의 재시도에 맡긴다. 그래서
     * 이 요청은 예산을 다 쓰고 끊긴다 — 끊는 주체가 실패가 아니라 <b>시계</b>라는 것이 요점이다.
     */
    @Test
    void 리빌드가_실패해도_낡은_회차로_답하지_않고_503이다() throws Exception {
        settleLoads();          // 문 앞에 서 있던 비행을 먼저 흘려보낸다
        failDownloads();
        rebuilder.bump(SnapshotCursorPolicy.ADVANCE);

        long startedAt = System.nanoTime();
        assertError(call(firstPageRequest()), 503, "PLACE-007");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(elapsedMs)
                .as("실패는 대기표를 깨우지 않는다 — 끊는 것은 요청 쪽 예산이다")
                .isGreaterThanOrEqualTo(REQUEST_WAIT_TIMEOUT_MS);
        verify(loader, org.mockito.Mockito.atLeastOnce()).readSourceState();
    }

    /**
     * <b>제때 못 따라잡아도 503이지 만료가 아니다.</b> 문을 닫아 둔 채로 데드라인을 넘긴다 —
     * 기다림의 상한이 실제로 걸리는지, 그리고 그것이 400으로 새지 않는지를 함께 본다.
     */
    @Test
    void 대기_시간을_넘기면_503_PLACE_007이다() throws Exception {
        rebuilder.bump(SnapshotCursorPolicy.ADVANCE);

        long startedAt = System.nanoTime();
        assertError(call(firstPageRequest()), 503, "PLACE-007");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(elapsedMs)
                .as("데드라인 전에 끊겼다면 기다림이 실제로 걸리지 않은 것이다")
                .isGreaterThanOrEqualTo(REQUEST_WAIT_TIMEOUT_MS);
    }

    // === 겹친 요청 ===

    /**
     * <b>밀린 요청 둘이 같은 순간에 들어와도 리빌드는 한 번이다.</b> 묶지 않으면 쓰기 직후 —
     * 즉 가장 바쁜 순간에 — place_stats 전량 읽기가 요청 수만큼 돈다.
     *
     * <p>MVC를 거치지 않고 조율자를 두 스레드에서 직접 부른다. 여기서 묻는 것이 HTTP 계약이 아니라
     * <b>인스턴스 안에서 몇 번 짓는가</b>라, MockMvc의 비동기 왕복을 끼우면 관측하려는 창이 두
     * 요청 사이에서 흐트러진다. 배선과 MySQL은 그대로 진짜다.
     */
    @Test
    void 동시에_밀린_요청_둘이_리빌드를_한_번만_유발한다() throws Exception {
        long newPlaceId = createPlace(townId, "회차대기동시", PLACE_CREATED_AT.plusMinutes(3));
        rebuilder.bump(SnapshotCursorPolicy.ADVANCE);
        long sharedCursorVersion = rebuilder.current().cursorVersion();

        CyclicBarrier startTogether = new CyclicBarrier(2);
        List<CompletableFuture<PlaceFilterGetResponse>> inFlight =
                Collections.synchronizedList(new ArrayList<>());
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread caller = new Thread(() -> {
                try {
                    startTogether.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                inFlight.add(orchestrator.getPlaces(userId, firstPageFilter()));
            }, "밀린요청" + i);
            callers.add(caller);
            caller.start();
        }
        for (Thread caller : callers) {
            caller.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));
        }
        openDownloads();

        assertThat(inFlight).hasSize(2);
        for (CompletableFuture<PlaceFilterGetResponse> response : inFlight) {
            assertThat(placeIdsOf(response.get(AWAIT_SECONDS, TimeUnit.SECONDS)))
                    .contains(newPlaceId);
        }
        assertThat(installer.installedCursorVersion()).isEqualTo(sharedCursorVersion);
        verify(loader, times(1)).readSourceState();
    }

    // === 문 ===

    /** 원본 읽기를 막는다. 열어 줄 때까지 어떤 경로도 리빌드를 끝내지 못한다 */
    private void gateDownloads() {
        downloadGate.set(new CountDownLatch(1));
        willAnswer(invocation -> {
            CountDownLatch current = downloadGate.get();
            if (current != null && !current.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("원본 읽기 문이 열리지 않았다 - 테스트가 멈춰 있다");
            }
            return invocation.callRealMethod();
        }).given(loader).readSourceState();
    }

    private void openDownloads() {
        CountDownLatch gate = downloadGate.getAndSet(null);
        if (gate != null) {
            gate.countDown();
        }
    }

    /**
     * 문을 열고 <b>설치가 따라잡을 때까지</b> 기다린다. 문 앞에 선 비행이 남아 있으면 그 다음에
     * 오는 요청이 그것에 붙어, 이번 테스트가 만들려던 상황이 아닌 것을 보게 된다.
     */
    private void settleLoads() {
        openDownloads();
        Awaitility.await().atMost(Duration.ofSeconds(AWAIT_SECONDS))
                .until(() -> {
                    installer.rebuildAndInstall();
                    return installer.installedRevision() == currentRevisionOnOwnConnection();
                });
    }

    /** 문을 여는 대신 던지게 한다 — 리빌드 실패가 요청에 어떻게 보이는지를 만든다 */
    private void failDownloads() {
        openDownloads();
        willAnswer(invocation -> {
            throw new IllegalStateException("원본 읽기 실패");
        }).given(loader).readSourceState();
    }

    // === MVC 왕복 ===

    /**
     * 요청을 끝까지 돌린다. 비동기로 넘어갔으면 <b>재디스패치까지</b> 마치고, 진입부 검증에서
     * 동기로 끊긴 요청은 그대로 돌려준다 — 두 모양 다 클라이언트에게는 한 번의 응답이다.
     */
    private MvcResult call(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mockMvc.perform(builder).andReturn();
        if (!result.getRequest().isAsyncStarted()) {
            return result;
        }
        return dispatch(result);
    }

    /** 비동기가 시작된 것까지 확인한다 — 기다림이 요청 스레드를 붙잡으면 이 단언이 먼저 깨진다 */
    private MvcResult startAsync(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder).andExpect(request().asyncStarted()).andReturn();
    }

    private MvcResult dispatch(MvcResult started) throws Exception {
        return mockMvc.perform(asyncDispatch(started)).andReturn();
    }

    private JsonNode successData(MvcResult result) throws Exception {
        JsonNode body = bodyOf(result);
        assertThat(result.getResponse().getStatus()).as("본문: %s", body).isEqualTo(200);
        assertThat(body.get("success").asBoolean()).isTrue();
        return body.get("data");
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        JsonNode body = bodyOf(result);
        assertThat(result.getResponse().getStatus()).as("본문: %s", body).isEqualTo(status);
        assertThat(body.get("success").asBoolean()).isFalse();
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("message").asText()).isNotBlank();
    }

    /**
     * 응답 본문은 <b>바이트에서 UTF-8로</b> 읽는다. {@code getContentAsString()}은 응답의
     * 문자셋을 따르는데 이 앱의 JSON 응답에는 그것이 붙어 있지 않아 한글이 깨진다 — 그 상태로
     * 비교하면 이름 단언이 통과할 수 없다.
     */
    private JsonNode bodyOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    /**
     * <b>파라미터는 여기서 한 번씩만 붙인다.</b> {@code MockHttpServletRequestBuilder#param}은
     * 같은 이름에 값을 <em>덧붙이므로</em>, 완성된 빌더에 {@code .param("size", ...)}을 한 번 더
     * 걸면 바인딩은 먼저 들어간 값을 쓴다 — 테스트가 의도한 요청과 실제로 나간 요청이 갈린다.
     */
    private MockHttpServletRequestBuilder listRequest(
            long town, boolean bookmarkSearch, String size, String cursor) {
        MockHttpServletRequestBuilder builder = get(PLACES_PATH)
                .param("townId", String.valueOf(town))
                .param("isBookmarkSearch", String.valueOf(bookmarkSearch))
                .param("sort", PlaceSortType.LATEST.name());
        if (size != null) {
            builder = builder.param("size", size);
        }
        if (cursor != null) {
            builder = builder.param("cursor", cursor);
        }
        return builder;
    }

    private MockHttpServletRequestBuilder firstPageRequest() {
        return listRequest(townId, false, "10", null);
    }

    private MockHttpServletRequestBuilder pageRequest(int size, String cursor) {
        return listRequest(townId, false, String.valueOf(size), cursor);
    }

    private PlaceFilterGetRequest firstPageFilter() {
        return new PlaceFilterGetRequest(
                townId, false, null, null, null, PlaceSortType.LATEST, null, 10, null, null);
    }

    // === 응답 읽기 ===

    private static List<Long> idsOf(JsonNode data) {
        List<Long> ids = new ArrayList<>();
        data.get("places").forEach(place -> ids.add(place.get("placeId").asLong()));
        return ids;
    }

    private static List<Long> placeIdsOf(PlaceFilterGetResponse response) {
        return response.places().stream().map(PlacePreviewDto::placeId).toList();
    }

    private static boolean bookmarkedFlagOf(JsonNode data, long placeId) {
        return placeNode(data, placeId).get("isBookmarked").asBoolean();
    }

    private static String nameOf(JsonNode data, long placeId) {
        return placeNode(data, placeId).get("placeName").asText();
    }

    /** 발급된 커서. <b>없으면 그 자리에서 끊는다</b> — {@code null}을 문자열로 흘려보내면 그 뒤의
     * 단언이 "커서가 거부됐다"로 통과해 버린다 */
    private static String issuedCursor(JsonNode data) {
        JsonNode cursor = data.get("nextCursor");
        if (cursor == null || cursor.isNull()) {
            throw new IllegalStateException("다음 커서가 발급되지 않았다: " + data);
        }
        return cursor.asText();
    }

    private static JsonNode placeNode(JsonNode data, long placeId) {
        for (JsonNode place : data.get("places")) {
            if (place.get("placeId").asLong() == placeId) {
                return place;
            }
        }
        throw new IllegalStateException("응답에 장소 " + placeId + "가 없다: " + data);
    }

    /** 같은 좌표에 회차 번호만 갈아 끼운 커서 — 다른 인스턴스가 발급했을 토큰과 같은 것이다 */
    private static String reversioned(String token, long version) {
        PlaceListCursor issued = PlaceListCursor.decode(token);
        return new PlaceListCursor(issued.sort(), issued.sortKeys(), issued.placeId(),
                issued.filterPrint(), version).encode();
    }

    // === 픽스처 ===

    /**
     * 공유 번호를 <b>스파이를 거치지 않고</b> 읽는다. 테스트가 세어 둔 호출 수를 뒷정리가
     * 흐트러뜨리지 않게 하려는 것이고, 문이 닫힌 상태에서도 읽히게 하려는 것이기도 하다.
     */
    private long currentRevisionOnOwnConnection() {
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT revision FROM place_list_snapshot_metadata WHERE id = 1")) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new IllegalStateException("스냅샷 번호 조회 실패", e);
        }
    }

    private long createTown(String name) {
        jdbcTemplate.update(
                "INSERT INTO towns (name, parent_id, active) VALUES (?, NULL, true)", name);
        return jdbcTemplate.queryForObject("SELECT MAX(id) FROM towns", Long.class);
    }

    /**
     * 장소 하나와 그 {@code place_stats} 행. 운영에서 그 행을 만드는 것은 어드민 쓰기
     * 트랜잭션이고, 어드민 경로를 거치지 않는 이 픽스처가 같은 자리를 채운다
     * ({@code PlaceListFlowIT}과 같은 사정 — 안 채우면 목록에 나오지 않는다).
     */
    private long createPlace(long town, String name, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO places (name, introduction, town_id, active, created_at)
                VALUES (?, '회차대기IT', ?, true, ?)""", name, town, createdAt);
        long placeId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM places", Long.class);
        jdbcTemplate.update("""
                INSERT INTO place_stats
                    (place_id, town_id, created_at, tag_bitmask, name, bookmark_count,
                     review_count, avg_rating)
                SELECT p.id, p.town_id, p.created_at, 0, p.name, 0, 0, 0
                FROM places p WHERE p.id = ? AND p.active = 1""", placeId);
        return placeId;
    }

    /** 커밋을 남기는 IT라 JUnit이 테스트마다 새로 만드는 인스턴스 필드로는 번호가 이어지지 않는다 */
    private static int seq = 0;

    private static synchronized int nextSeq() {
        return ++seq;
    }

    private long createUser() {
        String nickname = USER_NICKNAME_PREFIX + nextSeq() + "_" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO users (role, nickname) VALUES ('USER', ?)", nickname);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
    }

    private void insertBookmark(long user, long placeId, LocalDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO bookmarks (user_id, target_type, target_id, created_at, updated_at)
                VALUES (?, 'PLACE', ?, ?, ?)""", user, placeId, createdAt, createdAt);
    }

    /** {@code @SpringBootTest}는 롤백하지 않으므로 심은 행을 직접 지운다 */
    @AfterAll
    static void cleanUpCommittedFixtures() throws Exception {
        String myTowns = "SELECT id FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'";
        String myPlaces = "SELECT id FROM places WHERE town_id IN (" + myTowns + ")";
        try (Connection con = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                Statement st = con.createStatement()) {
            st.executeUpdate("DELETE FROM place_stats");
            st.executeUpdate("DELETE FROM bookmarks WHERE target_type = 'PLACE' AND target_id IN ("
                    + myPlaces + ")");
            st.executeUpdate("DELETE FROM bookmark_count_events WHERE target_id IN ("
                    + myPlaces + ")");
            st.executeUpdate("DELETE FROM courses WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate("DELETE FROM places WHERE town_id IN (" + myTowns + ")");
            st.executeUpdate(
                    "DELETE FROM users WHERE nickname LIKE '" + USER_NICKNAME_PREFIX + "%'");
            st.executeUpdate("DELETE FROM towns WHERE name LIKE '" + TOWN_NAME_PREFIX + "%'");
        }
    }
}
