package com.rewit.infrastructure.persistence.schema;

import javax.sql.DataSource;

/**
 * Ponte restrita ao classpath de teste para validar locations de teste com
 * {@link ReadOnlyFlywaySchemaValidator} sem ampliar a visibilidade em produção.
 */
public final class ReadOnlySchemaValidatorTestBridge {

    private ReadOnlySchemaValidatorTestBridge() {
    }

    public static void validateWithLocations(DataSource appDataSource, String locations) {
        new ReadOnlyFlywaySchemaValidator(appDataSource, locations).validate();
    }
}
