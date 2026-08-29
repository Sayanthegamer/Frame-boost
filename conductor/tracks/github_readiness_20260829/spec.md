# Specification: GitHub Readiness & Remote Push (`github_readiness`)

## Overview
This track prepares the entire Universal Frame Generation Minecraft mod (`framegen`) repository for open-source publication on GitHub, establishes comprehensive project documentation, licensing, automated CI builds, ensures repository hygiene, configures the remote repository (`https://github.com/Sayanthegamer/Frame-boost`), and pushes the codebase to the `main` branch.

## Functional Requirements
1. **Repository Documentation (`README.md`):**
   - Badges: Build status, Fabric Loader, Minecraft 26.2, Java 25, MIT License.
   - High-level overview & core value of Frame-boost (2x hardware-agnostic frame generation via camera matrix reprojection).
   - Key architectural components (`FrameCaptureManager`, `reproject.fsh`, `FramePacer`, controls).
   - System requirements (Java 25+, Minecraft 26.2, Fabric Loader).
   - Build instructions (`./gradlew build`) and installation guide.
2. **Open-Source Licensing (`LICENSE`):**
   - Standard MIT License with copyright holder `Sayanthegamer` (2026).
3. **Project Metadata Alignment (`fabric.mod.json`):**
   - Update authors (`Sayanthegamer`), homepage, sources, and issue tracker URLs to `https://github.com/Sayanthegamer/Frame-boost`.
4. **Repository Hygiene & `.gitignore`:**
   - Ensure comprehensive `.gitignore` coverage for Gradle, Loom, IDEs, OS files, and test/run outputs.
5. **Continuous Integration (`.github/workflows/build.yml`):**
   - GitHub Actions workflow with JDK 25 and Gradle build & unit test execution on push and PR to `main`.
   - Artifact uploading for compiled mod JARs.
6. **Git Remote & Branch Configuration:**
   - Rename default branch to `main`.
   - Set remote `origin` to `https://github.com/Sayanthegamer/Frame-boost.git`.
   - Push all commits to `origin/main` with upstream tracking.

## Acceptance Criteria
- `README.md` and `LICENSE` created at root.
- `fabric.mod.json` updated with correct repository and author info.
- `.github/workflows/build.yml` configured and valid.
- `.gitignore` verified.
- All unit tests pass locally (`./gradlew test`).
- Branch renamed to `main`, remote set, and code pushed to GitHub.

## Out of Scope
- Creating GitHub release tags or publishing to Modrinth / CurseForge (planned for subsequent release track).
- Modifying core rendering or shader logic.
