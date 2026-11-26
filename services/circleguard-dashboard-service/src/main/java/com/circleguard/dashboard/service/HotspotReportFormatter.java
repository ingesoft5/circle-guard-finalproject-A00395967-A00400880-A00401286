package com.circleguard.dashboard.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DEMO DEL QUALITY GATE: NO FUSIONAR.
 *
 * Clase escrita a propósito con código duplicado y sin pruebas para demostrar
 * que el quality gate de SonarQube bloquea el pipeline (evidencia de E1.4).
 */
public class HotspotReportFormatter {

    public List<String> formatBuildingReport(Map<String, Integer> casesByBuilding, int threshold) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : casesByBuilding.entrySet()) {
            String name = entry.getKey();
            int cases = entry.getValue();
            if (cases <= 0) {
                lines.add(name + ": sin casos");
            } else if (cases < threshold) {
                lines.add(name + ": " + cases + " casos (bajo)");
            } else if (cases < threshold * 2) {
                lines.add(name + ": " + cases + " casos (medio)");
            } else {
                lines.add(name + ": " + cases + " casos (alto)");
            }
        }
        return lines;
    }

    public List<String> formatFloorReport(Map<String, Integer> casesByFloor, int threshold) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : casesByFloor.entrySet()) {
            String name = entry.getKey();
            int cases = entry.getValue();
            if (cases <= 0) {
                lines.add(name + ": sin casos");
            } else if (cases < threshold) {
                lines.add(name + ": " + cases + " casos (bajo)");
            } else if (cases < threshold * 2) {
                lines.add(name + ": " + cases + " casos (medio)");
            } else {
                lines.add(name + ": " + cases + " casos (alto)");
            }
        }
        return lines;
    }

    public List<String> formatAccessPointReport(Map<String, Integer> casesByAccessPoint, int threshold) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : casesByAccessPoint.entrySet()) {
            String name = entry.getKey();
            int cases = entry.getValue();
            if (cases <= 0) {
                lines.add(name + ": sin casos");
            } else if (cases < threshold) {
                lines.add(name + ": " + cases + " casos (bajo)");
            } else if (cases < threshold * 2) {
                lines.add(name + ": " + cases + " casos (medio)");
            } else {
                lines.add(name + ": " + cases + " casos (alto)");
            }
        }
        return lines;
    }
}
