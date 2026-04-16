# Contributing to ForgeAI Pipeline Intelligence

Thank you for your interest in contributing! ForgeAI is an open-source project and we welcome contributions of all kinds.

## How to Contribute

### Reporting Bugs

Open an issue using the **Bug Report** template. Include:
- Jenkins version and OS
- ForgeAI plugin version
- LLM provider and model used
- Steps to reproduce
- Expected vs actual behavior
- Relevant console log output

### Suggesting Features

Open an issue using the **Feature Request** template. Describe:
- The problem you're solving
- Your proposed solution
- Alternatives you've considered

### Submitting Code

1. Fork the repo and create a feature branch from `develop`
2. Write your code following existing patterns
3. Add tests — aim for >80% coverage on new code
4. Run `mvn clean verify` locally before pushing
5. Open a PR against `develop` with a clear description

### Development Setup

```bash
# Prerequisites: JDK 17+, Maven 3.9+
git clone https://github.com/forgeai-oss/forgeai-pipeline-intelligence.git
cd forgeai-pipeline-intelligence
mvn clean verify          # Build + test
mvn hpi:run               # Launch local Jenkins with the plugin
```

### Code Style

- Java 17+ features are welcome (records, sealed classes, pattern matching)
- Follow existing package structure
- Javadoc on all public methods
- Use `@DataBoundSetter` / `@DataBoundConstructor` for Jenkins-persisted fields

### Adding a New Analyzer

1. Create a class extending `BaseAnalyzer` in `io.forgeai.jenkins.analyzers`
2. Implement `analyze()`, `analyzerName()`, `analyzerId()`
3. Register it in `ForgeAIStep.buildAnalyzerRegistry()`
4. Add a toggle in `ForgeAIGlobalConfiguration`
5. Add the toggle to `config.jelly`
6. Add tests
7. Update the README

## Code of Conduct

Be kind, be respectful, be constructive. We follow the [Contributor Covenant](https://www.contributor-covenant.org/).

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
