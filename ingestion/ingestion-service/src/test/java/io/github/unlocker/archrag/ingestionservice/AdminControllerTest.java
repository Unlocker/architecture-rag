package io.github.unlocker.archrag.ingestionservice;

import static org.mockito.ArgumentMatchers.any;
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
@WebMvcTest(AdminController.class)
@Import(SecurityConfiguration.class)
class AdminControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean JwtDecoder jwtDecoder;
  @MockitoBean AdminOperations operations;
  @MockitoBean ReplayService replay;
  @MockitoBean RebuildService rebuild;
  @MockitoBean CrosswalkService crosswalk;
  @MockitoBean ReconcileService reconcile;

  private static RequestPostProcessor admin() {
    return jwt().jwt(j -> j.subject("alice")).authorities(new SimpleGrantedAuthority("SCOPE_architecture.admin"));
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
  private static final String[][] ENDPOINTS = {
      {"/admin/replay", REPLAY_BODY},
      {"/admin/rebuild?confirm=true", ""},
      {"/admin/crosswalks", "[]"},
      {"/admin/reconcile/eam", ""},
  };

  @Test
  void withoutTokenIs401() throws Exception {
    for (String[] e : ENDPOINTS) {
      mvc.perform(post(e[0]).contentType(MediaType.APPLICATION_JSON).content(e[1])).andExpect(status().isUnauthorized());
    }
    verifyNoInteractions(replay, rebuild, crosswalk, reconcile, operations);
  }

  @Test
  void withoutAdminScopeIs403() throws Exception {
    for (String[] e : ENDPOINTS) {
      mvc.perform(post(e[0]).with(otherScope()).contentType(MediaType.APPLICATION_JSON).content(e[1]))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(replay, rebuild, crosswalk, reconcile, operations);
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
  void reconcileNormalizesSourceAndReturnsReport() throws Exception {
    when(reconcile.reconcile("urn:corp:eam")).thenReturn(
        new ReconcileResult("urn:corp:eam", "run-1", "snapshot-complete:run-1", new io.github.unlocker.archrag.graphprojector.Reconciler.Report(2, 0, 1, 5)));

    mvc.perform(post("/admin/reconcile/eam").with(admin())).andExpect(status().isOk())
        .andExpect(jsonPath("$.syncRunId").value("run-1")).andExpect(jsonPath("$.report.tombstoned").value(2));
    verify(operations).run(eq("RECONCILE"), eq("alice"), any(), eq(null), any(), any());
  }

  @Test
  void reconcileMapsNoSnapshotTo404AndIncompleteTo409() throws Exception {
    when(reconcile.reconcile("urn:corp:scm")).thenThrow(new SnapshotNotFoundException());
    when(reconcile.reconcile("urn:corp:cmdb")).thenThrow(new SnapshotNotCompleteException());

    mvc.perform(post("/admin/reconcile/scm").with(admin())).andExpect(status().isNotFound());
    mvc.perform(post("/admin/reconcile/cmdb").with(admin())).andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("SNAPSHOT_NOT_COMPLETE"));
    mvc.perform(post("/admin/reconcile/bad source").with(admin())).andExpect(status().isBadRequest());
  }

  @Test
  void otherPathsAreDenied() throws Exception {
    mvc.perform(post("/other").with(admin())).andExpect(status().isForbidden());
  }
}
