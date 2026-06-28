@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "cart :: api", "pricing :: api",
                "tax :: api", "payment :: api", "receipt :: api", "configuration :: api" })
package com.company.pos.sales;
