package com.company.pos.dining.web;

import com.company.pos.dining.api.DiningService;
import com.company.pos.dining.api.RegisterTableCommand;
import com.company.pos.dining.api.TableView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DiningController {

    private final DiningService dining;

    DiningController(DiningService dining) {
        this.dining = dining;
    }

    @PostMapping("/dining/tables")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MANAGER')")
    TableView registerTable(@RequestBody RegisterTableCommand body) {
        return dining.registerTable(body);
    }

    @DeleteMapping("/dining/tables/{tableId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('MANAGER')")
    void deactivateTable(@PathVariable UUID tableId) {
        dining.deactivateTable(tableId);
    }

    @GetMapping("/dining/tables")
    List<TableView> listTables() {
        return dining.listTables();
    }
}
