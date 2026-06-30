package com.company.pos.audit.application;

import com.company.pos.audit.api.AuditAction;
import com.company.pos.audit.api.AuditRecordView;
import com.company.pos.audit.api.AuditService;
import com.company.pos.audit.api.AuditVerifyResult;
import com.company.pos.audit.domain.AuditChainHead;
import com.company.pos.audit.domain.AuditRecord;
import com.company.pos.audit.infrastructure.AuditChainHeadRepository;
import com.company.pos.audit.infrastructure.AuditRecordRepository;
import com.company.pos.common.util.Identifiers;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultAuditService implements AuditService {

    private static final String GENESIS = "GENESIS";

    private final AuditRecordRepository records;
    private final AuditChainHeadRepository heads;
    private final ConfigurationService config;
    private final ObjectMapper objectMapper;

    DefaultAuditService(AuditRecordRepository records, AuditChainHeadRepository heads,
            ConfigurationService config, ObjectMapper objectMapper) {
        this.records = records;
        this.heads = heads;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /** Facade for non-transactional callers (auth): commit independently so a caller that then
     *  throws (e.g. a failed login) still leaves the record. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, String actor, String entityRef, Map<String, String> details) {
        doAppend(action, actor, entityRef, details);
    }

    /** Entry point for in-module event listeners: joins the listener's after-commit transaction so
     *  the insert and the listener's outbox completion are atomic. */
    @Transactional
    void append(AuditAction action, String actor, String entityRef, Map<String, String> details) {
        doAppend(action, actor, entityRef, details);
    }

    @Transactional(readOnly = true)
    public AuditVerifyResult verify() {
        String storeId = config.getString(SettingKey.STORE_ID);
        List<AuditRecord> chain = records.findByStoreIdOrderBySeqAsc(storeId);
        String prev = GENESIS;
        long checked = 0;
        for (AuditRecord r : chain) {
            String expected = HashChainer.chainHash(r.getSeq(), r.getOccurredAt(), r.getActor(),
                    r.getAction(), r.getEntityRef(), r.getPayload(), prev);
            if (!expected.equals(r.getHash()) || !prev.equals(r.getPrevHash())) {
                return new AuditVerifyResult(false, checked, r.getSeq());
            }
            prev = r.getHash();
            checked++;
        }
        return new AuditVerifyResult(true, checked, null);
    }

    @Transactional(readOnly = true)
    public List<AuditRecordView> recent(int limit) {
        String storeId = config.getString(SettingKey.STORE_ID);
        return records.findByStoreIdOrderBySeqDesc(storeId, org.springframework.data.domain.PageRequest.of(0, limit))
                .stream()
                .map(this::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AuditRecordView> search(String actor, String action, Instant from, Instant to,
            int page, int size) {
        return records.search(actor, action, from, to, PageRequest.of(page, size)).stream()
                .map(this::toView)
                .toList();
    }

    private void doAppend(AuditAction action, String actor, String entityRef,
            Map<String, String> details) {
        String storeId = config.getString(SettingKey.STORE_ID);
        AuditChainHead head = heads.findByStoreId(storeId)
                .orElseGet(() -> new AuditChainHead(storeId, 0L, GENESIS));
        long seq = head.getLastSeq() + 1;
        String prevHash = head.getLastHash();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String payload = canonicalJson(details);
        String hash = HashChainer.chainHash(seq, now, actor, action.name(), entityRef, payload, prevHash);
        records.save(new AuditRecord(Identifiers.newId(), seq, storeId, now, actor, action.name(),
                entityRef, payload, prevHash, hash));
        head.advance(seq, hash);
        heads.save(head);
    }

    private String canonicalJson(Map<String, String> details) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(details == null ? Map.of() : details));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize audit payload", e);
        }
    }

    private AuditRecordView toView(AuditRecord r) {
        return new AuditRecordView(r.getId(), r.getSeq(), r.getStoreId(), r.getOccurredAt(),
                r.getActor(), r.getAction(), r.getEntityRef(), r.getPayload(), r.getPrevHash(),
                r.getHash());
    }
}
