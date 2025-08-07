# Copilot Instructions for CLARIN-DSpace Repository

## Repository Overview

This repository contains **CLARIN-DSpace**, an institutional repository backend built on **DSpace v7**. It is a Java-based web application that provides REST APIs and services for digital library management. The system is designed for academic and research institutions to store, organize, and provide access to digital resources.

**Key Technologies:**
- **Language:** Java 11+ (currently Java 17 compatible)
- **Build System:** Apache Maven 3.x
- **Framework:** Spring Boot, Spring MVC, Spring HATEOAS
- **Testing:** JUnit, Maven Surefire (unit tests) and Failsafe (integration tests)
- **Database:** PostgreSQL with pgcrypto extension
- **Search:** Apache Solr 8.x
- **Container Support:** Docker and Docker Compose

**Repository Size:** ~32MB source code with 13 Maven modules, extensive test suite, and Docker configurations.

## Critical Build Information

### ⚠️ Known Network Dependency Issue
The repository has a **critical dependency on `handle.net` Maven repository** (`https://handle.net/maven`) for the Handle Server library (`net.handle:handle:jar:9.3.0`). This frequently causes build failures with DNS resolution errors:

```
Could not transfer artifact net.handle:handle:pom:9.3.0 from/to handle.net (https://handle.net/maven): handle.net: Temporary failure in name resolution
```

**Impact:** Standard Maven builds (`mvn compile`, `mvn install`) will likely fail in sandboxed or restricted network environments.

### Build Commands (When Network Available)

**Basic Build (Skip Tests):**
```bash
MAVEN_OPTS="-Xmx1024M" mvn clean install -DskipTests=true --no-transfer-progress -P-assembly
```

**Full Build with Tests:**
```bash
MAVEN_OPTS="-Xmx1024M" mvn clean install -DskipUnitTests=false -DskipIntegrationTests=false -P-assembly
```

**Required Memory Settings:**
- Always use `MAVEN_OPTS="-Xmx1024M"` for sufficient heap memory
- Use `--no-transfer-progress` to reduce log verbosity

## Testing Procedures

### Unit Tests
```bash
mvn test -DskipUnitTests=false -Dtest.argLine=-Xmx1024m -DsurefireJacoco=-XX:MaxPermSize=256m
```

### Integration Tests  
```bash
mvn install -DskipIntegrationTests=false -Dtest.argLine=-Xmx1024m -DfailsafeJacoco=-XX:MaxPermSize=256m -Dcheckstyle.skip=true -Dlicense.skip=true
```

### Single Test Execution
```bash
# Single unit test class
mvn test -DskipUnitTests=false -Dtest=[full.package.testClassName] -DfailIfNoTests=false

# Single integration test
mvn install -DskipIntegrationTests=false -Dit.test=[full.package.testClassName] -DfailIfNoTests=false -Dcheckstyle.skip -Dlicense.skip
```

### Test Module Isolation
Before running tests in a specific module, install dependencies:
```bash
mvn clean install -DskipTests=true
cd [module-directory]
# Run module-specific tests
```

## Code Quality and Validation

### Checkstyle (Works Reliably)
```bash
# Check entire project
mvn checkstyle:check --no-transfer-progress

# Check specific module
mvn checkstyle:check -f [module]/pom.xml --no-transfer-progress
```

**Checkstyle Rules:**
- 4-space indents for Java, 2-space for XML (NO TABS)
- K&R style braces on all blocks
- No wildcard imports
- Maximum 120 character line length
- Javadocs required for public classes/methods
- No trailing spaces (except comments)

### License Header Validation
```bash
mvn license:check --no-transfer-progress
```

### Pre-commit Hooks
The repository uses pre-commit hooks with checkstyle validation:
```bash
# Install pre-commit (if available)
pip install pre-commit
pre-commit install
```

## Docker Alternative (When Network Issues Occur)

If Maven builds fail due to network issues, Docker provides an alternative:

```bash
# Dependencies image (pre-caches Maven dependencies)
docker build -t dspace/dspace-dependencies:dspace-7_x -f Dockerfile.dependencies .

# Test image (includes both DSpace 7 REST API and legacy v6 API)
docker build -t dspace/dspace:dspace-7_x-test -f Dockerfile.test .

# Production image
docker build -t dspace/dspace:dspace-7_x -f Dockerfile .
```

**Docker Compose for Development:**
```bash
docker compose -f docker-compose.yml up -d
```

## Project Layout and Architecture

### Module Structure
```
/                           # Root with main pom.xml
├── dspace-parent/          # Parent POM configuration
├── dspace-services/        # Core services framework
├── dspace-api/            # Main API and business logic
├── dspace/                # Assembly and configuration
├── dspace-server-webapp/  # Spring Boot REST API webapp
├── dspace-iiif/          # IIIF integration
├── dspace-oai/           # OAI-PMH protocol support
├── dspace-rdf/           # RDF/Linked Data support
├── dspace-sword/         # SWORD protocol v1
├── dspace-swordv2/       # SWORD protocol v2
└── scripts/              # Build and utility scripts
```

### Key Configuration Files
- `pom.xml` - Main Maven configuration with Java 11 target
- `checkstyle.xml` - Code style rules and validation
- `checkstyle-suppressions.xml` - Style rule exceptions
- `.pre-commit-config.yaml` - Pre-commit hook configuration
- `docker-compose.yml` - Development environment setup

### Main Source Directories
- `dspace-server-webapp/src/main/java/org/dspace/app/rest/` - REST API controllers
- `dspace-api/src/main/java/org/dspace/` - Core business logic
- `dspace/config/` - DSpace configuration files
- `dspace/solr/` - Solr search configuration

## CI/CD Pipeline (.github/workflows/)

**Primary Build Workflow:** `.github/workflows/build.yml`
- Runs on Java 11 with matrix strategy for unit and integration tests
- Uses `MAVEN_OPTS="-Xmx1024M"` 
- Separate jobs for unit tests and integration tests
- Includes code coverage reporting
- **Triggered on:** pushes to `clarin-v7` and `customer/*` branches, plus all PRs

**Test Execution in CI:**
- Unit Tests: `mvn install -DskipUnitTests=false -Pdspace-rest -Dsurefire.rerunFailingTestsCount=2`
- Integration Tests: `mvn install -DskipIntegrationTests=false -Denforcer.skip=true -Dcheckstyle.skip=true -Dlicense.skip=true -Dxml.skip=true -Dfailsafe.rerunFailingTestsCount=2`

## Essential Commands Reference

**Clean Build (Most Reliable):**
```bash
mvn clean install -DskipTests=true --no-transfer-progress -P-assembly
```

**Code Quality Check:**
```bash
mvn checkstyle:check license:check --no-transfer-progress
```

**Resume Failed Build (After fixing issues):**
```bash
mvn <args> -rf :dspace-api  # Resume from dspace-api module
```

**Debug Build Issues:**
```bash
mvn <command> -X  # Enable debug logging
mvn <command> -e  # Show full stack traces
```

## Working with Network Restrictions

1. **Try checkstyle first** - it works independently: `mvn checkstyle:check`
2. **Use existing CI/CD pipeline** - view GitHub Actions results for build status
3. **Docker alternative** - if Docker has better network access than Maven
4. **Focus on local changes** - verify syntax, style, and logic before attempting builds
5. **Module-specific work** - edit individual modules and run checkstyle per module

## Trust These Instructions

These instructions are comprehensive and current as of DSpace 7.6.1. Only search for additional information if:
- You encounter errors not mentioned here
- You need specific configuration details not covered
- Network/dependency issues are resolved and you need full build procedures

The handle.net dependency issue is a known limitation that affects most builds in restricted environments. Focus on code quality validation and use CI/CD pipeline results to verify build success.