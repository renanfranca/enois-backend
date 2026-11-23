package com.socialcoisa.rede.notifications.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

@SpringBootTest
@AutoConfigureMockMvc
class NotificationContractControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbc;

	/** Instante fixo dos avisos que precisam de ordem e cursor verificáveis. */
	private static final Instant AT = Instant.parse("2026-01-10T12:00:00Z");

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void listsNotificationsAndMarksAllRead() throws Exception {
		var session = auth.authenticate("notif_user");

		// Sem conteudo, so o contrato do array vazio.
		mockMvc.perform(get("/notifications")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray());

		mockMvc.perform(post("/notifications/read-all")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "notif-read-all-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ALL_READ"));
	}

	@Test
	void marksASingleNotificationReadWithoutError() throws Exception {
		var session = auth.authenticate("notif_read_one");
		jdbc.update("""
				insert into notifications (id, user_id, kind, actor_id, title, body, is_read, created_at)
				values (?, ?, 'SYSTEM', 'sistema', 'Aviso', 'corpo', false, current_timestamp)
				""", "notif_read_id_1", session.userId());

		mockMvc.perform(post("/notifications/notif_read_id_1/read")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "notif-read-one"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/notifications")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == 'notif_read_id_1')].read").value(true));
	}

	@Test
	void unreadSummaryCountsUnreadPerAreaWithoutBlockedActorsAndHonorsTheContext() throws Exception {
		var session = auth.authenticate("notif_summary_owner");
		var blocked = auth.authenticate("notif_summary_blocked");
		String me = session.userId();
		unread(me, "summary_msg_1", "NEW_MESSAGE", "notif_summary_friend", false);
		unread(me, "summary_msg_2", "NEW_MESSAGE", "notif_summary_friend", false);
		unread(me, "summary_work_msg", "NEW_WORK_MESSAGE", "notif_summary_friend", false);
		unread(me, "summary_group", "GROUP_JOIN_REQUEST", "notif_summary_friend", false);
		unread(me, "summary_request", "CONNECTION_REQUEST", "notif_summary_friend", false);
		unread(me, "summary_accepted", "CONNECTION_ACCEPTED", "notif_summary_friend", false);
		unread(me, "summary_security", "SEGURANCA", "sistema", false);
		unread(me, "summary_read_group", "GROUP_NEW_POST", "notif_summary_friend", true);
		unread(me, "summary_blocked_group", "GROUP_NEW_POST", blocked.userId(), false);
		mockMvc.perform(post("/network/blocks/" + blocked.userId())
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "notif-summary-block"))
			.andExpect(status().isCreated());

		mockMvc.perform(get("/notifications/unread-summary").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			// Messages and connection requests have their own signals: only the notices count.
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.byModule.MESSAGES").doesNotExist())
			.andExpect(jsonPath("$.byModule.NETWORK").value(1))
			.andExpect(jsonPath("$.byModule.GROUPS").value(1))
			.andExpect(jsonPath("$.byModule.OTHER").value(1));

		// The professional interface keeps work kinds plus the account-wide ones (security).
		mockMvc.perform(get("/notifications/unread-summary").param("context", "PROFESSIONAL")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.byModule.MESSAGES").doesNotExist())
			.andExpect(jsonPath("$.byModule.OTHER").value(1))
			.andExpect(jsonPath("$.byModule.GROUPS").doesNotExist());

		mockMvc.perform(get("/notifications/unread-summary").param("context", "SOCIAL")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.byModule.MESSAGES").doesNotExist());

		mockMvc.perform(get("/notifications/unread-summary").param("context", "OTHER")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isBadRequest());
	}

	/**
	 * O `limit` vinha do cliente sem teto: uma aba aberta pedia a lista inteira de uma vez. A lista
	 * carrega aos poucos, como o feed e as mensagens, e o servidor nao confia no tamanho pedido.
	 */
	@Test
	void capsTheNotificationPageAtTwentyEvenWhenTheClientAsksForMore() throws Exception {
		var session = auth.authenticate("notif_page_cap");
		for (int index = 0; index < 25; index++) {
			unread(session.userId(), "cap_notif_" + index, "SISTEMA", "sistema", false);
		}

		mockMvc.perform(get("/notifications").param("limit", "100")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(20));

		// Pedido invalido nao vira pagina vazia: cai no piso de um.
		mockMvc.perform(get("/notifications").param("limit", "0")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1));
	}

	@Test
	void everyListedItemCarriesTheModuleOfItsKind() throws Exception {
		var session = auth.authenticate("notif_module_owner");
		unread(session.userId(), "module_event", "EVENT_NEXT_OCCURRENCE", "notif_module_friend", false);
		unread(session.userId(), "module_reaction", "NEW_REACTION", "notif_module_friend", false);

		mockMvc.perform(get("/notifications").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == 'module_event')].module").value("EVENTS"))
			.andExpect(jsonPath("$.items[?(@.id == 'module_reaction')].module").value("OTHER"));
	}

	@Test
	void unreadOnlyListsEveryUnreadKindNewestFirstAndTheDefaultListStaysTheSame() throws Exception {
		var session = auth.authenticate("notif_unread_kinds");
		String me = session.userId();
		notice(me, "unread_kinds_system", "SISTEMA", "sistema", true, AT.plusSeconds(60));
		notice(me, "unread_kinds_message", "NEW_MESSAGE", "notif_unread_friend", false, AT.plusSeconds(50));
		notice(me, "unread_kinds_group", "GROUP_NEW_POST", "notif_unread_friend", true, AT.plusSeconds(40));
		notice(me, "unread_kinds_request", "CONNECTION_REQUEST", "notif_unread_friend", false, AT.plusSeconds(30));
		notice(me, "unread_kinds_reaction", "NEW_REACTION", "notif_unread_friend", false, AT.plusSeconds(20));

		String unreadOnly = list(session.token(), "unread", "true");
		String withoutFilter = list(session.token());
		String explicitlyUnfiltered = list(session.token(), "unread", "false");

		assertThat(ids(unreadOnly)).containsExactly("unread_kinds_message", "unread_kinds_request",
				"unread_kinds_reaction");
		assertThat(JsonPath.<List<Boolean>>read(unreadOnly, "$.items[*].read")).containsOnly(false);
		assertThat(ids(withoutFilter)).containsExactly("unread_kinds_system", "unread_kinds_message",
				"unread_kinds_group", "unread_kinds_request", "unread_kinds_reaction");
		assertThat(ids(explicitlyUnfiltered)).isEqualTo(ids(withoutFilter));
	}

	@Test
	void unreadOnlyLeavesOutBlockedActors() throws Exception {
		var session = auth.authenticate("notif_unread_blocker");
		var blocked = auth.authenticate("notif_unread_blocked_actor");
		String me = session.userId();
		notice(me, "blocking_unread_from_blocked_actor", "NEW_REACTION", blocked.userId(), false, AT.plusSeconds(30));
		notice(me, "blocking_read_from_friend", "NEW_REACTION", "notif_unread_friend", true, AT.plusSeconds(20));
		notice(me, "blocking_unread_from_friend", "NEW_REACTION", "notif_unread_friend", false, AT.plusSeconds(10));
		mockMvc.perform(post("/network/blocks/" + blocked.userId())
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "notif-unread-blocker-block"))
			.andExpect(status().isCreated());

		String unreadOnly = list(session.token(), "unread", "true");

		assertThat(ids(unreadOnly)).containsExactly("blocking_unread_from_friend");
	}

	/**
	 * O sino conta a não lida pelo resumo, mas ela ficava na página 2 da lista atrás de dez avisos
	 * lidos mais novos (enois-frontend#1147). Com o filtro, ela vem na primeira página.
	 */
	@Test
	void unreadOnlyBringsAnOldUnreadNoticeHiddenBehindTenNewerReadOnes() throws Exception {
		var session = auth.authenticate("notif_unread_buried");
		String me = session.userId();
		notice(me, "unread_buried_reaction", "NEW_REACTION", "notif_unread_friend", false, AT);
		for (int index = 1; index <= 10; index++) {
			notice(me, "unread_buried_read_" + index, index % 2 == 0 ? "NEW_MESSAGE" : "NEW_REACTION",
				"notif_unread_friend", true, AT.plusSeconds(index));
		}

		String defaultPage = list(session.token(), "limit", "10");
		String unreadPage = list(session.token(), "unread", "true", "limit", "10");
		String summary = mockMvc.perform(get("/notifications/unread-summary")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(ids(defaultPage)).hasSize(10).doesNotContain("unread_buried_reaction");
		assertThat(ids(unreadPage)).containsExactly("unread_buried_reaction");
		assertThat((Integer) JsonPath.read(summary, "$.total")).isEqualTo(1);
	}

	@Test
	void unreadOnlyPagesThroughTheUnreadNoticesWithTheCursor() throws Exception {
		var session = auth.authenticate("notif_unread_pages");
		String me = session.userId();
		notice(me, "unread_pages_read_1", "NEW_REACTION", "notif_unread_friend", true, AT.plusSeconds(70));
		notice(me, "unread_pages_a", "NEW_REACTION", "notif_unread_friend", false, AT.plusSeconds(60));
		notice(me, "unread_pages_read_2", "NEW_REACTION", "notif_unread_friend", true, AT.plusSeconds(50));
		notice(me, "unread_pages_b", "NEW_REACTION", "notif_unread_friend", false, AT.plusSeconds(40));
		notice(me, "unread_pages_read_3", "NEW_REACTION", "notif_unread_friend", true, AT.plusSeconds(30));
		notice(me, "unread_pages_c", "NEW_REACTION", "notif_unread_friend", false, AT.plusSeconds(20));
		notice(me, "unread_pages_read_4", "NEW_REACTION", "notif_unread_friend", true, AT.plusSeconds(10));

		String first = list(session.token(), "unread", "true", "limit", "2");
		String cursor = JsonPath.read(first, "$.nextCursor");
		String second = list(session.token(), "unread", "true", "limit", "2", "cursor", cursor);

		assertThat(ids(first)).containsExactly("unread_pages_a", "unread_pages_b");
		assertThat(cursor).isNotBlank();
		assertThat(ids(second)).containsExactly("unread_pages_c");
		assertThat((String) JsonPath.read(second, "$.nextCursor")).isNull();
	}

	@Test
	void unreadOnlyCombinesWithTheContext() throws Exception {
		var session = auth.authenticate("notif_unread_context");
		String me = session.userId();
		notice(me, "unread_context_work", "NEW_WORK_MESSAGE", "notif_unread_friend", false, AT.plusSeconds(30));
		notice(me, "unread_context_security", "SEGURANCA", "sistema", false, AT.plusSeconds(20));
		notice(me, "unread_context_group", "GROUP_NEW_POST", "notif_unread_friend", false, AT.plusSeconds(10));
		notice(me, "unread_context_read_work", "NEW_WORK_MESSAGE", "notif_unread_friend", true, AT.plusSeconds(5));
		notice(me, "unread_context_read_group", "GROUP_NEW_POST", "notif_unread_friend", true, AT);

		String professional = list(session.token(), "unread", "true", "context", "PROFESSIONAL");
		String social = list(session.token(), "unread", "true", "context", "SOCIAL");

		assertThat(ids(professional)).containsExactly("unread_context_work", "unread_context_security");
		assertThat(ids(social)).containsExactly("unread_context_security", "unread_context_group");
	}

	@Test
	void refusesAnUnreadFilterThatIsNotABoolean() throws Exception {
		var session = auth.authenticate("notif_unread_invalid");

		mockMvc.perform(get("/notifications").param("unread", "talvez")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
	}

	@Test
	void everyListedItemCarriesItsPlaceAndEventDateOrNull() throws Exception {
		var session = auth.authenticate("notif_place_owner");
		String me = session.userId();
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String community = community("place-community-" + suffix, "Mesa de BH");
		String post = communityPost(community);
		String group = "place-group-" + suffix;
		String closedGroup = "place-closed-" + suffix;
		group(group, "Grupo da Roda", "ACTIVE");
		group(closedGroup, "Grupo Fechado", "CLOSED");
		String today = LocalDate.now(ZoneId.of("America/Sao_Paulo")).toString();
		String event = event("place-event-" + suffix, today);
		String brEvent = event("place-br-event-" + suffix, "24/12/2026");
		unread(me, "place_community", "MENTION", "notif_place_friend", false, "/comunidades/" + community);
		unread(me, "place_post", "NEW_REACTION", "notif_place_friend", false, "/api/feed/posts/" + post + "?reacoes=1");
		unread(me, "place_group", "GROUP_NEW_POST", "notif_place_friend", false, "/grupos/" + group);
		unread(me, "place_event", "EVENT_NEXT_OCCURRENCE", "notif_place_friend", false, "/eventos?evento=" + event);
		unread(me, "place_br_event", "EVENT_NEXT_OCCURRENCE", "notif_place_friend", false, "/eventos?evento=" + brEvent);
		unread(me, "place_gone_community", "MENTION", "notif_place_friend", false, "/comunidades/gone-" + suffix);
		unread(me, "place_closed_group", "GROUP_NEW_POST", "notif_place_friend", false, "/grupos/" + closedGroup);
		unread(me, "place_gone_event", "EVENT_NEXT_OCCURRENCE", "notif_place_friend", false, "/eventos?evento=gone");
		unread(me, "place_none", "SEGURANCA", "sistema", false, "/configuracoes/seguranca");
		unread(me, "place_no_href", "SISTEMA", "sistema", false, null);

		mockMvc.perform(get("/notifications").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == 'place_community')].place.type").value("COMMUNITY"))
			.andExpect(jsonPath("$.items[?(@.id == 'place_community')].place.id").value(community))
			.andExpect(jsonPath("$.items[?(@.id == 'place_community')].place.name").value("Mesa de BH"))
			.andExpect(jsonPath("$.items[?(@.id == 'place_community')].place.color").value((Object) null))
			.andExpect(jsonPath("$.items[?(@.id == 'place_community')].eventDate").value((Object) null))
			.andExpect(jsonPath("$.items[?(@.id == 'place_post')].place.type").value("COMMUNITY"))
			.andExpect(jsonPath("$.items[?(@.id == 'place_post')].place.id").value(community))
			.andExpect(jsonPath("$.items[?(@.id == 'place_group')].place.type").value("GROUP"))
			.andExpect(jsonPath("$.items[?(@.id == 'place_group')].place.name").value("Grupo da Roda"))
			.andExpect(jsonPath("$.items[?(@.id == 'place_event')].eventDate").value(today))
			.andExpect(jsonPath("$.items[?(@.id == 'place_event')].place").value((Object) null))
			.andExpect(jsonPath("$.items[?(@.id == 'place_br_event')].eventDate").value("2026-12-24"))
			.andExpect(jsonPath("$.items[?(@.id == 'place_gone_community')].place").value((Object) null))
			.andExpect(jsonPath("$.items[?(@.id == 'place_closed_group')].place").value((Object) null))
			.andExpect(jsonPath("$.items[?(@.id == 'place_gone_event')].eventDate").value((Object) null))
			.andExpect(jsonPath("$.items[?(@.id == 'place_none')].place").value((Object) null))
			.andExpect(jsonPath("$.items[?(@.id == 'place_none')].eventDate").value((Object) null))
			.andExpect(jsonPath("$.items[?(@.id == 'place_no_href')].place").value((Object) null));
	}

	@Test
	void unreadSummaryCountsPerCommunityAndTheConfirmedEventsOfToday() throws Exception {
		var session = auth.authenticate("notif_summary_places");
		String me = session.userId();
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		String community = community("summary-community-" + suffix, "Mesa");
		String other = community("summary-other-" + suffix, "Outra mesa");
		String post = communityPost(community);
		unread(me, "summary_place_1", "MENTION", "notif_place_friend", false, "/comunidades/" + community);
		unread(me, "summary_place_2", "NEW_REACTION", "notif_place_friend", false, "/api/feed/posts/" + post);
		unread(me, "summary_place_3", "NEW_COMMENT", "notif_place_other", false, "/api/feed/posts/" + post);
		unread(me, "summary_place_read", "MENTION", "notif_place_friend", true, "/comunidades/" + other);
		unread(me, "summary_place_gone", "MENTION", "notif_place_friend", false, "/comunidades/gone-" + suffix);
		LocalDate today = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
		attend(me, event("summary-today-" + suffix, today.toString()));
		attend(me, event("summary-today-br-" + suffix,
			today.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))));
		attend(me, event("summary-tomorrow-" + suffix, today.plusDays(1).toString()));
		event("summary-not-attending-" + suffix, today.toString());

		mockMvc.perform(get("/notifications/unread-summary").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.byCommunity.length()").value(1))
			.andExpect(jsonPath("$.byCommunity['" + community + "']").value(3))
			.andExpect(jsonPath("$.eventsToday").value(2));

		// Events are social: the professional interface has none, and no community notification.
		mockMvc.perform(get("/notifications/unread-summary").param("context", "PROFESSIONAL")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.byCommunity.length()").value(0))
			.andExpect(jsonPath("$.eventsToday").value(0));
	}

	@Test
	void unreadSummaryHasNoCommunitiesNorEventsWhenThereAreNone() throws Exception {
		var session = auth.authenticate("notif_summary_empty");

		mockMvc.perform(get("/notifications/unread-summary").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(0))
			.andExpect(jsonPath("$.byCommunity.length()").value(0))
			.andExpect(jsonPath("$.eventsToday").value(0));
	}

	private String community(String id, String name) {
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("insert into communities (id, name, description, topic, created_by, created_at, updated_at) "
			+ "values (?, ?, 'desc', 'TECNOLOGIA', 'notif_place_friend', ?, ?)", id, name, now, now);
		return id;
	}

	private String communityPost(String communityId) {
		String id = "place-post-" + UUID.randomUUID();
		jdbc.update("insert into posts (id, author_id, type, content, created_at, visibility, reactions, comments, "
			+ "saves, shares, status, community_id) values (?, 'notif_place_friend', 'INSIGHT', 'oi', ?, 'PUBLIC', 0, 0, 0,"
			+ " 0, 'ACTIVE', ?)", id, Timestamp.from(Instant.now()), communityId);
		return id;
	}

	private void group(String slug, String name, String status) {
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("insert into groups (id, slug, name, owner_id, status, created_at, updated_at) "
			+ "values (?, ?, ?, 'notif_place_friend', ?, ?, ?)", "id-" + slug, slug, name, status, now, now);
	}

	private String event(String id, String date) {
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("insert into events (id, title, date, time, format, host, created_by, created_at, updated_at) "
			+ "values (?, 'Encontro', ?, '19:00', 'ONLINE', 'Casa', 'notif_place_friend', ?, ?)", id, date, now, now);
		return id;
	}

	private void attend(String userId, String eventId) {
		jdbc.update("insert into event_attendees (event_id, user_id, responded_at) values (?, ?, ?)", eventId, userId,
			Timestamp.from(Instant.now()));
	}

	private void unread(String userId, String id, String kind, String actorId, boolean read, String href) {
		jdbc.update("""
				insert into notifications (id, user_id, kind, actor_id, title, body, href, is_read, created_at)
				values (?, ?, ?, ?, 'Aviso', 'corpo', ?, ?, current_timestamp)
				""", id, userId, kind, actorId, href, read);
	}

	private void notice(String userId, String id, String kind, String actorId, boolean read, Instant createdAt) {
		jdbc.update("""
				insert into notifications (id, user_id, kind, actor_id, title, body, is_read, created_at)
				values (?, ?, ?, ?, 'Aviso', 'corpo', ?, ?)
				""", id, userId, kind, actorId, read, Timestamp.from(createdAt));
	}

	private String list(String token, String... params) throws Exception {
		var request = get("/notifications").header("X-Session-Token", token);
		for (int index = 0; index < params.length; index += 2) {
			request.param(params[index], params[index + 1]);
		}
		return mockMvc.perform(request)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private static List<String> ids(String page) {
		return JsonPath.read(page, "$.items[*].id");
	}

	private void unread(String userId, String id, String kind, String actorId, boolean read) {
		jdbc.update("""
				insert into notifications (id, user_id, kind, actor_id, title, body, is_read, created_at)
				values (?, ?, ?, ?, 'Aviso', 'corpo', ?, current_timestamp)
				""", id, userId, kind, actorId, read);
	}
}
