---
name: new-module
description: Conventions for adding a new Spring Modulith module to the POS modular monolith — package layout, named interfaces, allowed-dependency declarations, and the wiring a new module needs. Read before scaffolding a module.
---

# Add a Spring Modulith module

Each POS module lives under `com.company.pos.<module>` and follows a hexagonal
layout enforced by `ModularityTests` (`modules.verify()`). Mirror an existing
module like `cashdrawer` rather than inventing structure.

## Package layout
```
com.company.pos.<module>/
  package-info.java          # @ApplicationModule(allowedDependencies = {...})
  web/                       # @RestController endpoints (optional)
  api/
    package-info.java        # @NamedInterface("api")
    <Module>Service.java     # public interface other modules call
    *View.java / *.java      # DTOs exposed across module boundary
  application/
    Default<Module>Service.java   # impl of the api interface
    *Listener.java                # @ApplicationModuleListener for events
  domain/                    # JPA entities, value objects (module-internal)
  infrastructure/            # Spring Data repositories (module-internal)
```

## Required declarations
- **Module root** `package-info.java`:
  ```java
  @org.springframework.modulith.ApplicationModule(
          allowedDependencies = { "common", "database", "otherModule :: api" })
  package com.company.pos.<module>;
  ```
  List ONLY the modules you depend on. Cross-module access must go through the
  other module's `:: api` named interface — never its `domain`/`infrastructure`.
- **api** `package-info.java`:
  ```java
  @org.springframework.modulith.NamedInterface("api")
  package com.company.pos.<module>.api;
  ```

## Cross-module communication
- **Synchronous read** → call another module's `api` service interface.
- **Reacting to something that happened** → publish/consume domain events with
  `@ApplicationModuleListener` (see `cashdrawer`'s `SaleCompletedCashListener`,
  which reacts to `sales`' `SaleCompleted`). Prefer events over direct calls for
  write-side coupling.

## Wiring checklist
1. If the module persists data: add a Flyway migration (use the
   **create-migration** skill) and append its folder to `flyway.locations` in
   `application-store-server.yml`.
2. If it's a foundational/phase module asserted by name, add it to the relevant
   assertion in `src/test/java/com/company/pos/ModularityTests.java`.
3. Run `JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -q test` —
   `ModularityTests.verifiesModuleBoundaries` fails loudly on any illegal
   dependency or missing named interface.
