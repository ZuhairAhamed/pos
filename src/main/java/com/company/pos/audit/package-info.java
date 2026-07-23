@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "sales :: api", "product :: api",
                "dining :: api", "menu :: api", "configuration :: api" })
package com.company.pos.audit;
