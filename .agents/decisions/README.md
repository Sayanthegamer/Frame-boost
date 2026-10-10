# Architectural Decision Records (ADRs)

This directory contains ratified Architectural Decision Records governed by Cadence Two-Tier Governance.

Decisions classified as **Tier 1 (Foundational / Consequential)** materially affect one or more of the 8 Material Impact Vectors:
1. Numerical Results or Physical Correctness
2. Reproducibility or Determinism
3. Precision or Error Tolerances
4. Performance Characteristics or Scaling Behavior
5. Memory Layout or Data Movement
6. Concurrency, Synchronization, or Execution Model
7. Public or Module Interfaces
8. Algorithmic, Mathematical, Rendering, or External Dependency Choices

| ADR | Title | Status | Date | Material Impact Vectors |
|---|---|---|---|---|
| [ADR-0001](./ADR-0001-matrix-reprojection-reverse-z-depth.md) | Hardware-Agnostic 2x Matrix Reprojection with Reverse-Z Depth Unprojection | Accepted | 2026-08-28 | Vector 1 (Correctness), Vector 4 (Performance), Vector 7 (Interfaces), Vector 8 (Algorithms) |
| [ADR-0002](./ADR-0002-angular-velocity-gating-entity-stencil-masking.md) | Forward-Only Velocity Gating, Dynamic Entity Stencil Masking, and Epipolar Infill | Accepted | 2026-08-31 | Vector 1 (Correctness), Vector 4 (Performance), Vector 6 (Execution Model), Vector 8 (Algorithms) |
