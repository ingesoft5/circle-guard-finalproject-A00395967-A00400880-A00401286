package com.circleguard.dashboard.service;

import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * DEMO DEL QUALITY GATE: NO FUSIONAR.
 *
 * Errores introducidos a propósito para que SonarQube los marque como
 * bloqueantes y el quality gate detenga el pipeline:
 *  - bucle infinito (bug bloqueante),
 *  - recurso que nunca se cierra (bug bloqueante),
 *  - conexión a base de datos sin contraseña (vulnerabilidad bloqueante).
 */
public class HotspotExportJob {

    public void waitForExport() {
        int attempts = 0;
        while (true) {
            attempts++;
        }
    }

    public int readFirstByte(String path) throws IOException {
        FileInputStream in = new FileInputStream(path);
        return in.read();
    }

    public Connection openReportsDatabase() throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://localhost:5432/reports", "admin", "");
    }
}
