@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "sales :: api", "product :: api",
                "configuration :: api" })
package com.company.pos.audit;
