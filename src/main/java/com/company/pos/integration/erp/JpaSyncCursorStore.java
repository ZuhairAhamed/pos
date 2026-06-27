package com.company.pos.integration.erp;

import com.company.pos.integration.api.SyncCursorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class JpaSyncCursorStore implements SyncCursorStore {

    private final SyncCursorRepository repository;

    JpaSyncCursorStore(SyncCursorRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public long get(String stream) {
        return repository.findById(stream).map(SyncCursor::getValue).orElse(0L);
    }

    @Override
    public void set(String stream, long value) {
        SyncCursor cursor = repository.findById(stream).orElseGet(() -> new SyncCursor(stream, value));
        cursor.setValue(value);
        repository.save(cursor);
    }
}
