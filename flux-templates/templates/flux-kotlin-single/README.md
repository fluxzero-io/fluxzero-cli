# Flux Kotlin Example Project

## Requirements 

- JDK 21+
- IntelliJ IDEA with Detekt and ktlin plugins installed


## Developer experience in this project

- **Detekt** - consistent code styling enforced and on pre-commit validated (see `config/detekt/detekt.yml`)
- Clean Gradle project using `libs.versions.toml` to manage versions
- Tuned IntelliJ configuration 
  - Out of the box run configurations to start Flux Server, Flux Proxy and your Flux Application
  - Java 21 
  - Flux annotated components are not marked as unused
  - Flux live templates to generate common Flux code samples 
- Example application that:
  - Demonstrates core components of Flux
  - Provides test cases
  - Has sample http requests (see `.test/requests`)
  - Enables kotlin support 