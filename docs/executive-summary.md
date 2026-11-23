# CircleGuard: Executive Summary (Part 2)

*Software Engineering V · Universidad Icesi · Team of 3 · Sprint 2 (Nov 17–22, 2026)*

## Problem

CircleGuard is a university contact-tracing system: it identifies contact groups (*Circles*) and enforces health containment without exposing student identity. It has 8 Spring Boot microservices (Java 21), a web client, and middleware (PostgreSQL, Neo4j, Kafka, Redis, OpenLDAP). After Part 1 (CI/CD with Jenkins, Docker Compose on Azure VMs), the system lacked reproducible infrastructure, a Kubernetes deployment, observability, resilience patterns, and a safe promotion process between environments. The instructor also flagged two process risks: oversized pull requests that cannot be reviewed, and version names that do not distinguish unvalidated builds from validated releases.

## Solution

- **Infrastructure as Code:** Terraform (`infra/terraform`) provisions the network, the k3s cluster VM, the tools VM (Jenkins, SonarQube, Nexus), Key Vaults, and backup storage, with remote state in Azure.
- **Kubernetes:** one Helm chart (`deploy/helm/circleguard`) deployed to three namespaces (`circleguard-dev`, `-stage`, `-prod`), with Traefik ingress, TLS via cert-manager, and HPAs on gateway, form and promotion.
- **Pipeline:** Jenkins runs build, unit tests with JaCoCo (≥ 60 %), SonarQube gate, integration tests, Trivy, deploy to dev, Playwright E2E, OWASP ZAP, stage, manual approval, and prod.
- **Versioning:** builds are published as `vX.Y.Z-SNAPSHOT.<N>`; after approval the *same image* is re-tagged `vX.Y.Z` (the only tag allowed in prod).
- **Resilience and configuration:** Circuit Breaker (dashboard → promotion), Retry with backoff (auth → identity), and a Feature Toggle (`circleguard.features.strict-gate.enabled`).
- **Process:** a GitHub Action (`pr-size`) caps PRs at 400 changed lines, excluding generated files.

## Architecture

Users reach Traefik over HTTPS; nginx (`web`) proxies to the 8 services, which use PostgreSQL, Neo4j, Kafka, Redis and OpenLDAP running as StatefulSets with local-path volumes. Prometheus, Grafana (8 technical dashboards and 1 business dashboard), Jaeger, Loki and Alloy run in the `observability` namespace. Diagrams: `docs/arquitectura/kubernetes.md` and `docs/arquitectura/terraform.md`.

## Key results

| Area | Result | Source |
|---|---|---|
| Unit-test coverage (JaCoCo) | auth 98.1 %, identity 94.1 %, promotion 81.4 %, form 97.7 %, gateway 88.9 % (target ≥ 60 %, enforced by the build) | `docs/pruebas/cobertura.md` |
| E2E tests | 3 Playwright flows (login, questionnaire, campus access) gate promotion to stage | `docs/pruebas/e2e.md` |
| Security | Trivy blocks fixable CRITICAL findings; 3 Spring CVEs accepted temporarily, review date 2026-12-20 | `docs/change-management/riesgos-aceptados.md` |
| Load test (Locust) vs. target | [TBD] | pending consolidated report (CG-111) |
| ZAP findings | [TBD] | pending consolidated report (CG-111) |
| Trivy totals per image | [TBD] | pending consolidated report (CG-111) |
| Real Azure cost vs. estimate | [TBD] | `docs/infra/costos.md` |
| Rollback exercise (N → N-1 → N) | [TBD] | `docs/change-management/rollback.md` |

## Lessons learned

1. **Small PRs make review real.** An automatic size check is cheaper than relying on discipline.
2. **Immutable, promoted artifacts.** Re-tagging the tested image avoids the Part 1 problem where `v1.0.0` was tagged in Git but never reached production.
3. **Security debt must be explicit.** Accepted CVEs carry an owner, a control and an expiry date, rather than being silently ignored.
4. **Dependencies between teammates need milestones.** Fixed hand-off points (H0–H5) exposed delays early.
5. **Next steps:** migrate to Spring Boot 3.5+ to remove the accepted CVEs, and retire stale feature toggles.
