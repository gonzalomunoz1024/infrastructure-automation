package com.iaf.console.bff.jobs.application;

import java.util.ArrayList;
import java.util.List;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.jobs.domain.ApprovalView;
import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.model.Approval;
import com.iaf.console.platform.model.DecisionRequest;

import reactor.core.publisher.Mono;

/** Approvals waiting for the user and the ones they asked for, and the decision on one. */
public class Approvals {

    public static final String SERVICE = "approvals";

    private final PlatformClients clients;
    private final Access access;
    private final Trust trust;
    private final PlatformProperties platform;

    public Approvals(PlatformClients clients, Access access, Trust trust, PlatformProperties platform) {
        this.clients = clients;
        this.access = access;
        this.trust = trust;
        this.platform = platform;
    }

    public Mono<Page<ApprovalView>> list(Session session, String status, Integer limit, String after) {
        int size = CursorPaging.pageSize(limit);
        int offset = after == null || after.isBlank() ? 0 : parseOffset(after);
        return mayDecide(session).flatMap(policyAllows -> clients.forAccount(CurrentSession.scope(session)).flatMap(p -> p.listApprovals(status)).map(all -> {
            List<ApprovalView> views = new ArrayList<>();
            for (Approval a : all) {
                views.add(ApprovalView.of(a, session.currentAccount().groups(), policyAllows));
            }
            int end = Math.min(views.size(), offset + size);
            List<ApprovalView> page = offset >= views.size() ? List.of() : views.subList(offset, end);
            return new Page<>(page, end < views.size() ? Integer.toString(end) : null);
        }));
    }

    /** Whether the account's policies let the user decide approvals at all; the group check is per approval. */
    public Mono<Boolean> mayDecide(Session session) {
        String resource = AccessCheck.servicePattern(platform.partition(), SERVICE, session.region(), session.accountId());
        return access.decide(session, List.of(new AccessCheck(AccessCheck.action(SERVICE, "decide"), resource))).map(d -> d.get(0).allowed());
    }

    public Mono<Void> decide(Session session, String approvalId, String decision, String comment, String requestId, String idempotencyKey) {
        if (!"approve".equals(decision) && !"reject".equals(decision)) {
            return Mono.error(new IllegalArgumentException("decision is approve or reject"));
        }
        String resource = AccessCheck.servicePattern(platform.partition(), SERVICE, session.region(), session.accountId());
        return access.require(session, AccessCheck.action(SERVICE, "decide"), resource)
                .then(clients.forAccount(CurrentSession.scope(session)))
                .flatMap(p -> p.getApproval(approvalId)
                        .onErrorResume(PlatformException.class, e -> e.isNotFound() || e.isForbidden() ? Mono.error(NotFoundException.of("approval", approvalId))
                                : Mono.error(e))
                        .flatMap(approval -> {
                            DecisionRequest request = new DecisionRequest(comment);
                            var options = OnBehalfOf.options(trust, session, requestId, idempotencyKey);
                            return "approve".equals(decision) ? p.approve(approvalId, request, options) : p.reject(approvalId, request, options);
                        }))
                .then();
    }

    private static int parseOffset(String after) {
        try {
            return Math.max(0, Integer.parseInt(after.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("the cursor is not one this list issued");
        }
    }
}
