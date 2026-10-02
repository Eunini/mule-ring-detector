package io.github.eunini.mrd.cases.web;

import io.github.eunini.mrd.cases.service.AlertIngestService;
import io.github.eunini.mrd.cases.service.ApiException;
import io.github.eunini.mrd.cases.web.dto.AlertPayload;
import io.github.eunini.mrd.cases.web.dto.IngestResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Engine-facing ingestion endpoint. Accepts a JSON array of alerts or a single alert object. */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    static final int MAX_BATCH = 1000;

    private final AlertIngestService ingestService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public AlertController(AlertIngestService ingestService, ObjectMapper objectMapper, Validator validator) {
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public IngestResponse ingest(@RequestBody JsonNode body, Principal principal) {
        List<AlertPayload> alerts = parse(body);
        validate(alerts);
        return ingestService.ingest(alerts, principal.getName());
    }

    private List<AlertPayload> parse(JsonNode body) {
        List<JsonNode> nodes = new ArrayList<>();
        if (body == null || body.isNull()) {
            throw ApiException.badRequest("request body is required");
        } else if (body.isArray()) {
            body.forEach(nodes::add);
        } else if (body.isObject()) {
            nodes.add(body);
        } else {
            throw ApiException.badRequest("expected an alert object or an array of alerts");
        }
        if (nodes.isEmpty()) {
            throw ApiException.badRequest("no alerts in request");
        }
        if (nodes.size() > MAX_BATCH) {
            throw ApiException.badRequest("batch too large: " + nodes.size() + " > " + MAX_BATCH);
        }
        List<AlertPayload> alerts = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            JsonNode node = nodes.get(i);
            if (!node.isObject()) {
                throw ApiException.badRequest("alert [" + i + "] is not a JSON object");
            }
            try {
                alerts.add(objectMapper.treeToValue(node, AlertPayload.class));
            } catch (Exception e) {
                throw ApiException.badRequest("alert [" + i + "] is malformed: " + rootMessage(e));
            }
        }
        return alerts;
    }

    private void validate(List<AlertPayload> alerts) {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < alerts.size(); i++) {
            int index = i;
            validator.validate(alerts.get(i)).stream()
                    .sorted(Comparator.comparing((ConstraintViolation<AlertPayload> v) -> v.getPropertyPath().toString()))
                    .forEach(v -> errors.add("[" + index + "]." + v.getPropertyPath() + ": " + v.getMessage()));
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest("invalid alert payload", errors);
        }
    }

    private static String rootMessage(Throwable e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }
}
