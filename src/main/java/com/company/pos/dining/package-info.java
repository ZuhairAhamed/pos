@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "common", "database",
            "product :: api", "cart :: api", "sales :: api", "configuration :: api",
            "menu :: api" })
package com.company.pos.dining;
