@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "common", "database",
            "configuration :: api", "dining :: api", "device :: api" })
package com.company.pos.kitchen;
