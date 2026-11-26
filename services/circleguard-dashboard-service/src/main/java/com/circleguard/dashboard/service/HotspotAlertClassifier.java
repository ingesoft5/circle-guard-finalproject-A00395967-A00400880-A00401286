package com.circleguard.dashboard.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DEMO DEL QUALITY GATE: NO FUSIONAR.
 *
 * Segunda clase con código duplicado y sin pruebas. Se agrega en un commit
 * posterior al primer análisis de SonarQube para que cuente como código nuevo.
 */
public class HotspotAlertClassifier {

    public List<String> classifyBuildingAlerts(Map<String, Integer> casesByBuilding, int threshold) {
        List<String> alerts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : casesByBuilding.entrySet()) {
            String name = entry.getKey();
            int cases = entry.getValue();
            if (cases <= 0) {
                alerts.add(name + ": sin alerta");
            } else if (cases < threshold) {
                alerts.add(name + ": alerta baja (" + cases + ")");
            } else if (cases < threshold * 2) {
                alerts.add(name + ": alerta media (" + cases + ")");
            } else if (cases < threshold * 3) {
                alerts.add(name + ": alerta alta (" + cases + ")");
            } else {
                alerts.add(name + ": alerta crítica (" + cases + ")");
            }
        }
        return alerts;
    }

    public List<String> classifyFloorAlerts(Map<String, Integer> casesByFloor, int threshold) {
        List<String> alerts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : casesByFloor.entrySet()) {
            String name = entry.getKey();
            int cases = entry.getValue();
            if (cases <= 0) {
                alerts.add(name + ": sin alerta");
            } else if (cases < threshold) {
                alerts.add(name + ": alerta baja (" + cases + ")");
            } else if (cases < threshold * 2) {
                alerts.add(name + ": alerta media (" + cases + ")");
            } else if (cases < threshold * 3) {
                alerts.add(name + ": alerta alta (" + cases + ")");
            } else {
                alerts.add(name + ": alerta crítica (" + cases + ")");
            }
        }
        return alerts;
    }

    public List<String> classifyAccessPointAlerts(Map<String, Integer> casesByAccessPoint, int threshold) {
        List<String> alerts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : casesByAccessPoint.entrySet()) {
            String name = entry.getKey();
            int cases = entry.getValue();
            if (cases <= 0) {
                alerts.add(name + ": sin alerta");
            } else if (cases < threshold) {
                alerts.add(name + ": alerta baja (" + cases + ")");
            } else if (cases < threshold * 2) {
                alerts.add(name + ": alerta media (" + cases + ")");
            } else if (cases < threshold * 3) {
                alerts.add(name + ": alerta alta (" + cases + ")");
            } else {
                alerts.add(name + ": alerta crítica (" + cases + ")");
            }
        }
        return alerts;
    }

    public List<String> classifyDepartmentAlerts(Map<String, Integer> casesByDepartment, int threshold) {
        List<String> alerts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : casesByDepartment.entrySet()) {
            String name = entry.getKey();
            int cases = entry.getValue();
            if (cases <= 0) {
                alerts.add(name + ": sin alerta");
            } else if (cases < threshold) {
                alerts.add(name + ": alerta baja (" + cases + ")");
            } else if (cases < threshold * 2) {
                alerts.add(name + ": alerta media (" + cases + ")");
            } else if (cases < threshold * 3) {
                alerts.add(name + ": alerta alta (" + cases + ")");
            } else {
                alerts.add(name + ": alerta crítica (" + cases + ")");
            }
        }
        return alerts;
    }

    public List<String> classifyFacultyAlerts(Map<String, Integer> casesByFaculty, int threshold) {
        List<String> alerts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : casesByFaculty.entrySet()) {
            String name = entry.getKey();
            int cases = entry.getValue();
            if (cases <= 0) {
                alerts.add(name + ": sin alerta");
            } else if (cases < threshold) {
                alerts.add(name + ": alerta baja (" + cases + ")");
            } else if (cases < threshold * 2) {
                alerts.add(name + ": alerta media (" + cases + ")");
            } else if (cases < threshold * 3) {
                alerts.add(name + ": alerta alta (" + cases + ")");
            } else {
                alerts.add(name + ": alerta crítica (" + cases + ")");
            }
        }
        return alerts;
    }
}
