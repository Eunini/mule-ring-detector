package io.github.eunini.mrd.cases.service;

import io.github.eunini.mrd.cases.domain.AlertEntity;
import io.github.eunini.mrd.cases.web.dto.AlertPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Serialises alert payloads to the evidence_json column and back. */
@Component
public class EvidenceReader {

    private final ObjectMapper objectMapper;

    public EvidenceReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(AlertPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise alert evidence", e);
        }
    }

    public AlertPayload read(AlertEntity alert) {
        try {
            return objectMapper.readValue(alert.getEvidenceJson(), AlertPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt evidence for alert " + alert.getAlertId(), e);
        }
    }
}
