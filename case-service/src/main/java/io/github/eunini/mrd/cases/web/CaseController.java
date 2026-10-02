package io.github.eunini.mrd.cases.web;

import io.github.eunini.mrd.cases.domain.CaseStatus;
import io.github.eunini.mrd.cases.service.CaseQueryService;
import io.github.eunini.mrd.cases.service.CaseWorkflowService;
import io.github.eunini.mrd.cases.service.ReportService;
import io.github.eunini.mrd.cases.web.dto.CaseDtos.AuditEventView;
import io.github.eunini.mrd.cases.web.dto.CaseDtos.CaseDetail;
import io.github.eunini.mrd.cases.web.dto.CaseDtos.CaseListItem;
import io.github.eunini.mrd.cases.web.dto.CaseDtos.PageResponse;
import io.github.eunini.mrd.cases.web.dto.CaseRequests.AssignRequest;
import io.github.eunini.mrd.cases.web.dto.CaseRequests.CommentRequest;
import io.github.eunini.mrd.cases.web.dto.CaseRequests.FilingDecisionBody;
import io.github.eunini.mrd.cases.web.dto.CaseRequests.FilingRejectionBody;
import io.github.eunini.mrd.cases.web.dto.CaseRequests.FilingRequestBody;
import io.github.eunini.mrd.cases.web.dto.CaseRequests.TransitionRequest;
import io.github.eunini.mrd.cases.web.dto.GraphView;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final CaseQueryService queries;
    private final CaseWorkflowService workflow;
    private final ReportService reports;

    public CaseController(CaseQueryService queries, CaseWorkflowService workflow, ReportService reports) {
        this.queries = queries;
        this.workflow = workflow;
        this.reports = reports;
    }

    @GetMapping
    public PageResponse<CaseListItem> list(
            @RequestParam(name = "status", required = false) List<CaseStatus> status,
            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return queries.list(status, pageable);
    }

    @GetMapping("/{id}")
    public CaseDetail get(@PathVariable Long id) {
        return queries.detail(id);
    }

    @GetMapping("/{id}/audit")
    public List<AuditEventView> audit(@PathVariable Long id) {
        return queries.audit(id);
    }

    @GetMapping("/{id}/graph")
    public GraphView graph(@PathVariable Long id) {
        return reports.graph(id);
    }

    @PostMapping("/{id}/assign")
    public CaseDetail assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request, Principal principal) {
        workflow.assign(id, request.assignee(), principal.getName());
        return queries.detail(id);
    }

    @PostMapping("/{id}/transition")
    public CaseDetail transition(@PathVariable Long id, @Valid @RequestBody TransitionRequest request,
                                 Principal principal) {
        workflow.transition(id, request.to(), request.disposition(), request.comment(), principal.getName());
        return queries.detail(id);
    }

    @PostMapping("/{id}/comments")
    public List<AuditEventView> comment(@PathVariable Long id, @Valid @RequestBody CommentRequest request,
                                        Principal principal) {
        workflow.comment(id, request.text(), principal.getName());
        return queries.audit(id);
    }

    @PostMapping("/{id}/filing-request")
    public CaseDetail requestFiling(@PathVariable Long id,
                                    @Valid @RequestBody(required = false) FilingRequestBody body,
                                    Principal principal) {
        workflow.requestFiling(id, body == null ? null : body.comment(), principal.getName());
        return queries.detail(id);
    }

    @PostMapping("/{id}/filing-approval")
    public CaseDetail approveFiling(@PathVariable Long id,
                                    @Valid @RequestBody(required = false) FilingDecisionBody body,
                                    Principal principal) {
        workflow.approveFiling(id, body == null ? null : body.comment(), principal.getName());
        return queries.detail(id);
    }

    @PostMapping("/{id}/filing-rejection")
    public CaseDetail rejectFiling(@PathVariable Long id, @Valid @RequestBody FilingRejectionBody body,
                                   Principal principal) {
        workflow.rejectFiling(id, body.reason(), principal.getName());
        return queries.detail(id);
    }
}
