@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "common", "database", "cart :: api", "sales :: api" })
package com.company.pos.customer;
