package io.github.unlocker.archrag.ingestionservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.NotQuarantinedException;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.eventschemas.UnknownEventException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Контроллер: scope {@code architecture.admin} (401/403/200), проверка параметров, 409 при занятом замке. */
// issuer-uri и audiences нужны только чтобы разрешились плейсхолдеры application.yml; декодер токенов подменён моком.
@WebMvcTest(value = AdminController.class,
    properties = {
      "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://unused.invalid",
      "spring.security.oauth2.resourceserver.jwt.audiences=http://unused.invalid"
    })
@Import(SecurityConfiguration.class)
class AdminControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean JwtDecoder jwtDecoder;
  @MockitoBean AdminOperations operations;
  @MockitoBean ReplayService replay;
  @MockitoBean RebuildService rebuild;
  @MockitoBean CrosswalkService crosswalk;
  @MockitoBean CandidateApprovalService candidateApproval;
  @MockitoBean AdapterControlClient adapters;
  @MockitoBean EventJournal journal;

  private static RequestPostProcessor admin() {
    return jwt().jwt(j -> j.subject("alice")).authorities(new SimpleGrantedAuthority("SCOPE_architecture.admin"),
        new SimpleGrantedAuthority("ROLE_archrag-console-admin"));
  }

  /** Scope верный, realm-роли {@code archrag-console-admin} нет. */
  private static RequestPostProcessor scopeWithoutRole() {
    return jwt().jwt(j -> j.subject("carol")).authorities(new SimpleGrantedAuthority("SCOPE_architecture.admin"));
  }

  /** Роль верная, scope {@code architecture.admin} нет. */
  private static RequestPostProcessor roleWithoutScope() {
    return jwt().jwt(j -> j.subject("dave")).authorities(new SimpleGrantedAuthority("ROLE_archrag-console-admin"));
  }

  private static RequestPostProcessor otherScope() {
    return jwt().jwt(j -> j.subject("bob")).authorities(new SimpleGrantedAuthority("SCOPE_architecture.read"));
  }

  @SuppressWarnings("unchecked")
  @BeforeEach
  void operationsRunBody() {
    when(operations.run(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
      Supplier<Object> body = inv.getArgument(4);
      Function<Object, Map<String, ?>> summary = inv.getArgument(5);
      Object result = body.get();
      summary.apply(result);
      return result;
    });
    when(operations.locked(any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(0)).get());
  }

  private static final String REPLAY_BODY = "{\"source\":\"urn:corp:eam\"}";
  private static final String RELEASE_BODY = "{\"source\":\"urn:corp:eam\",\"eventId\":\"e1\"}";
  private static final String APPROVE_BODY =
      "{\"left\":{\"source\":\"CMDB\",\"sourceType\":\"COMPUTE_INSTANCE\",\"sourceId\":\"1\"},"
          + "\"right\":{\"source\":\"CMDB\",\"sourceType\":\"COMPUTE_INSTANCE\",\"sourceId\":\"2\"},"
          + "\"survivor\":\"RIGHT\",\"reason\":\"same host\"}";
  private static final String[][] ENDPOINTS = {
      {"/admin/identity/candidates/approve", APPROVE_BODY},
      {"/admin/replay", REPLAY_BODY},
      {"/admin/rebuild?confirm=true", ""},
      {"/admin/crosswalks", "[]"},
      {"/admin/reconcile/eam", ""},
      {"/admin/quarantine/release", RELEASE_BODY},
  };

  @Test
  void withoutTokenIs401() throws Exception {
    for (String[] e : ENDPOINTS) {
      mvc.perform(post(e[0]).contentType(MediaType.APPLICATION_JSON).content(e[1])).andExpect(status().isUnauthorized());
    }
    verifyNoInteractions(replay, rebuild, crosswalk, candidateApproval, adapters, operations, journal);
  }

  @Test
  void withoutAdminScopeIs403() throws Exception {
    for (String[] e : ENDPOINTS) {
      mvc.perform(post(e[0]).with(otherScope()).contentType(MediaType.APPLICATION_JSON).content(e[1]))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(replay, rebuild, crosswalk, candidateApproval, adapters, operations, journal);
  }

  @Test
  void adminScopeWithoutRoleOrRoleWithoutScopeIs403() throws Exception {
    for (RequestPostProcessor caller : java.util.List.of(scopeWithoutRole(), roleWithoutScope())) {
      for (String[] e : ENDPOINTS) {
        mvc.perform(post(e[0]).with(caller).contentType(MediaType.APPLICATION_JSON).content(e[1]))
            .andExpect(status().isForbidden());
      }
    }
    verifyNoInteractions(replay, rebuild, crosswalk, candidateApproval, adapters, operations, journal);
  }

  @Test
  void replayWithScopeReturnsResultAndPassesActor() throws Exception {
    String id = UUID.randomUUID().toString();
    when(replay.replay(eq("urn:corp:eam"), any(), any(), eq(id))).thenReturn(new ReplayResult(id, 3, Map.of("PROJECTED", 3L)));

    mvc.perform(post("/admin/replay").with(admin()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"source\":\"urn:corp:eam\",\"replayId\":\"" + id + "\",\"receivedFrom\":\"2026-10-01T00:00:00Z\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.replayId").value(id))
        .andExpect(jsonPath("$.statuses.PROJECTED").value(3));
    verify(operations).run(eq("REPLAY"), eq("alice"), any(), eq(id), any(), any());
  }

  private static JournalEntry released(int attempts) {
    return new JournalEntry("urn:corp:eam", "e1", null, null, "APPLICATION", "1", new SourceVersion("1"), "1",
        ProcessingStatus.RETRYING, null, null, attempts, null, Instant.EPOCH, Instant.EPOCH);
  }

  @Test
  void releaseFromQuarantineReturnsRetryingAndIsAudited() throws Exception {
    when(journal.releaseFromQuarantine("urn:corp:eam", "e1")).thenReturn(released(0));

    mvc.perform(post("/admin/quarantine/release").with(admin()).contentType(MediaType.APPLICATION_JSON)
            .content(RELEASE_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RETRYING"))
        .andExpect(jsonPath("$.attempts").value(0));
    verify(operations).run(eq("QUARANTINE_RELEASE"), eq("alice"), any(), any(), any(), any());
  }

  @Test
  void releaseOfEventNotInQuarantineIs409AndUnknownEventIs404() throws Exception {
    when(journal.releaseFromQuarantine("urn:corp:eam", "e1")).thenThrow(new NotQuarantinedException("x"));
    when(journal.releaseFromQuarantine("urn:corp:eam", "e2")).thenThrow(new UnknownEventException("x"));

    mvc.perform(post("/admin/quarantine/release").with(admin()).contentType(MediaType.APPLICATION_JSON)
            .content(RELEASE_BODY))
        .andExpect(status().isConflict());
    mvc.perform(post("/admin/quarantine/release").with(admin()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"source\":\"urn:corp:eam\",\"eventId\":\"e2\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void releaseInfrastructureFailureIsNotMappedTo404Or409() throws Exception {
    when(journal.releaseFromQuarantine("urn:corp:eam", "e1")).thenThrow(new IllegalStateException("admin lock failed"));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> mvc.perform(post("/admin/quarantine/release")
            .with(admin()).contentType(MediaType.APPLICATION_JSON).content(RELEASE_BODY)))
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void releaseRejectsBadParameters() throws Exception {
    for (String body : List.of("{}", "{\"source\":\"urn:corp:eam\"}", "{\"source\":\"urn:corp:eam\",\"eventId\":\" \"}",
        "{\"eventId\":\"e1\"}", "{\"source\":\"bad source/\",\"eventId\":\"e1\"}",
        "{\"source\":\"urn:corp:eam\",\"eventId\":\"" + "x".repeat(501) + "\"}")) {
      mvc.perform(post("/admin/quarantine/release").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(journal);
  }

  @Test
  void replayRejectsBadParameters() throws Exception {
    for (String body : List.of("{}", "{\"source\":\"bad source/\"}",
        "{\"source\":\"urn:corp:eam\",\"replayId\":\"nope\"}",
        "{\"source\":\"urn:corp:eam\",\"receivedFrom\":\"2026-10-02T00:00:00Z\",\"receivedTo\":\"2026-10-01T00:00:00Z\"}")) {
      mvc.perform(post("/admin/replay").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(replay);
  }

  @Test
  void rebuildRequiresConfirm() throws Exception {
    mvc.perform(post("/admin/rebuild").with(admin())).andExpect(status().isBadRequest());
    mvc.perform(post("/admin/rebuild?confirm=false").with(admin())).andExpect(status().isBadRequest());
    verify(rebuild, never()).rebuild(any());
  }

  @Test
  void rebuildWithConfirmRuns() throws Exception {
    when(rebuild.rebuild(any())).thenAnswer(inv -> new ReplayResult(inv.getArgument(0), 1, Map.of("PROJECTED", 1L)));
    mvc.perform(post("/admin/rebuild?confirm=true").with(admin())).andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
  }

  @Test
  void busyLockIs409() throws Exception {
    doThrow(new AdminBusyException()).when(operations).run(any(), any(), any(), any(), any(), any());
    mvc.perform(post("/admin/rebuild?confirm=true").with(admin())).andExpect(status().isConflict());
  }

  @Test
  void crosswalksPassSubjectAsApprover() throws Exception {
    var result = new CrosswalkResult(0, CrosswalkResult.Status.CONFLICT, null);
    when(crosswalk.load(anyList(), eq("alice"))).thenReturn(List.of(result));
    String body = "[{\"left\":{\"source\":\"EAM\",\"sourceType\":\"IT_SYSTEM\",\"sourceId\":\"1\"},"
        + "\"right\":{\"source\":\"SCM\",\"sourceType\":\"CATALOG_SYSTEM\",\"sourceId\":\"2\"},\"reason\":\"same\"}]";

    mvc.perform(post("/admin/crosswalks").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("CONFLICT"));
    mvc.perform(post("/admin/crosswalks").with(admin()).contentType(MediaType.APPLICATION_JSON).content("[]"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void candidateApprovalPassesSubjectAndSurvivorAndAuditsIt() throws Exception {
    UUID gid = UUID.randomUUID();
    UUID previous = UUID.randomUUID();
    when(candidateApproval.approve(any())).thenReturn(
        new CandidateApprovalResult(CandidateApprovalResult.Status.REPROJECTED, gid, previous, true));

    mvc.perform(post("/admin/identity/candidates/approve").with(admin()).contentType(MediaType.APPLICATION_JSON)
            .content(APPROVE_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REPROJECTED"))
        .andExpect(jsonPath("$.gid").value(gid.toString()))
        .andExpect(jsonPath("$.previousRetired").value(true));

    var captor = org.mockito.ArgumentCaptor.forClass(io.github.unlocker.archrag.identityresolution.CandidateApproval.class);
    verify(candidateApproval).approve(captor.capture());
    assertThat(captor.getValue().approvedBy()).isEqualTo("alice");
    assertThat(captor.getValue().survivor()).isEqualTo(io.github.unlocker.archrag.identityresolution.CandidateApproval.Survivor.RIGHT);
    assertThat(captor.getValue().survivorKey().sourceId()).isEqualTo("2");
    assertThat(captor.getValue().movedKey().sourceId()).isEqualTo("1");
    verify(operations).run(eq("IDENTITY_APPROVE"), eq("alice"), any(), eq(null), any(), any());
  }

  @Test
  void candidateApprovalMapsUnknownTo404OpenlessTo409AndReprojectionFailureTo500() throws Exception {
    when(candidateApproval.approve(any()))
        .thenThrow(new io.github.unlocker.archrag.identityresolution.CandidateNotFoundException("x"))
        .thenThrow(new io.github.unlocker.archrag.identityresolution.CandidateNotOpenException("x"))
        .thenThrow(new ReprojectionException("NO_RAW_EVENT", "no raw"));

    for (int expected : new int[] {404, 409, 500}) {
      mvc.perform(post("/admin/identity/candidates/approve").with(admin()).contentType(MediaType.APPLICATION_JSON)
              .content(APPROVE_BODY))
          .andExpect(status().is(expected));
    }
  }

  @Test
  void candidateApprovalRejectsBadParametersWithoutCallingTheService() throws Exception {
    String key = "{\"source\":\"CMDB\",\"sourceType\":\"COMPUTE_INSTANCE\",\"sourceId\":\"1\"}";
    for (String body : List.of(
        "{}",
        "{\"left\":" + key + ",\"right\":" + key + ",\"survivor\":\"LEFT\",\"reason\":\"r\"}",
        APPROVE_BODY.replace("RIGHT", "BOTH"),
        APPROVE_BODY.replace("\"survivor\":\"RIGHT\",", ""),
        APPROVE_BODY.replace("same host", " "),
        APPROVE_BODY.replace("CMDB", "NOPE"))) {
      mvc.perform(post("/admin/identity/candidates/approve").with(admin()).contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(candidateApproval);
  }

  @Test
  void reconcileProxiesToAdapterAndPassesStatusAsIs() throws Exception {
    when(adapters.knows("eam")).thenReturn(true);
    when(adapters.snapshot(eq("eam"), anyString())).thenReturn(new AdapterControlClient.Response(202, "{\"outcome\":\"SNAPSHOT_COMPLETED\"}"));

    mvc.perform(post("/admin/reconcile/urn:corp:eam").with(admin())).andExpect(status().isAccepted())
        .andExpect(jsonPath("$.outcome").value("SNAPSHOT_COMPLETED"));
    verify(operations).run(eq("RECONCILE"), eq("alice"), any(), eq(null), any(), any());

    when(adapters.snapshot(eq("eam"), anyString())).thenReturn(new AdapterControlClient.Response(409, "{\"outcome\":\"ALREADY_RUNNING\"}"));
    mvc.perform(post("/admin/reconcile/eam").with(admin())).andExpect(status().isConflict());
  }

  @Test
  void reconcileUnknownSourceIs404AndUnavailableAdapterIs502() throws Exception {
    when(adapters.knows("scm")).thenReturn(true);
    when(adapters.snapshot(eq("scm"), anyString())).thenThrow(new AdapterUnavailableException());

    mvc.perform(post("/admin/reconcile/nope").with(admin())).andExpect(status().isNotFound());
    mvc.perform(post("/admin/reconcile/scm").with(admin())).andExpect(status().isBadGateway());
    mvc.perform(post("/admin/reconcile/bad source").with(admin())).andExpect(status().isBadRequest());
    verify(adapters, never()).snapshot(eq("nope"), anyString());
  }

  @Test
  void otherPathsAreDenied() throws Exception {
    mvc.perform(post("/other").with(admin())).andExpect(status().isForbidden());
  }
}
