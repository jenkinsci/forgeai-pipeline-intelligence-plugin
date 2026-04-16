# ForgeAI Pipeline Intelligence

### AI-Powered Code Reviews, Security Analysis, Architecture Drift Detection & Release Readiness — Directly in Your Jenkins Pipeline

[![CI](https://github.com/forgeai-oss/forgeai-pipeline-intelligence/actions/workflows/ci.yml/badge.svg)](https://github.com/forgeai-oss/forgeai-pipeline-intelligence/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Jenkins Plugin](https://img.shields.io/badge/Jenkins-2.426.3%2B-blue.svg)](https://www.jenkins.io/)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net)

---

## Table of Contents

- [Why ForgeAI?](#why-forgeai)
- [Analyzers](#analyzers)
- [Supported LLM Backends](#supported-llm-backends)
- [Quick Start](#quick-start)
- [Pipeline DSL Reference](#pipeline-dsl-reference)
- [Air-Gapped / Local LLM Setup](#air-gapped--local-llm-setup)
- [HTML Report](#html-report)
- [Configuration Reference](#configuration-reference)
- [Building from Source](#building-from-source)
- [Project Structure](#project-structure)
- [Requirements](#requirements)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

---

## Why ForgeAI?

Every CI/CD pipeline runs linters and tests — but they miss the **architectural**, **strategic**, and **contextual** issues that only experienced engineers catch. ForgeAI bridges that gap by embedding AI-powered intelligence directly into your Jenkins pipeline.

ForgeAI is not another ChatGPT wrapper. It is a purpose-built pipeline intelligence engine with:

- **8 specialized analyzers**, each with expert-level system prompts tuned for its domain
- **Architecture-aware analysis** that understands hexagonal, layered, CQRS, and microservice patterns
- **Composite scoring** that weighs security 3× and architecture 2× — because not all findings are equal
- **Release readiness verdicts** (SHIP_IT / CAUTION / HOLD / BLOCK) that synthesize all analyses
- **Zero-cloud mode** via Ollama for air-gapped and regulated environments
- **A self-contained HTML report** archived with every build

---

## Analyzers

| Analyzer | ID | What It Does |
|---|---|---|
| **AI Code Review** | `code-review` | SOLID, DRY, naming, error handling, anti-patterns, readability |
| **Vulnerability Analysis** | `vulnerability` | OWASP Top 10, hardcoded secrets, injection, CWE mapping |
| **Architecture Drift Detection** | `architecture-drift` | Layer violations, circular deps, coupling, pattern enforcement |
| **Test Gap Analysis** | `test-gaps` | Untested paths, missing edge cases, test quality, concrete suggestions |
| **Dependency Risk Scoring** | `dependency-risk` | License conflicts, unmaintained deps, unpinned versions, duplication |
| **Commit Intelligence** | `commit-intel` | Commit hygiene, breaking change detection, changelog & semver suggestions |
| **Pipeline Optimizer** | `pipeline-advisor` | Parallelization, caching, resource waste, failure resilience |
| **Release Readiness** | `release-readiness` | Composite verdict synthesizing all prior analyses |

---

## Supported LLM Backends

ForgeAI is **provider-agnostic**. Use whatever fits your security and budget requirements:

| Provider | Type | API Key Required | Air-Gapped |
|---|---|---|---|
| **OpenAI** (GPT-4o, GPT-4o-mini, o1) | Cloud API | Yes | No |
| **Anthropic Claude** (claude-sonnet-4-5, claude-opus-4-5) | Cloud API | Yes | No |
| **Ollama** (DeepSeek-Coder, CodeLlama, Llama 3, Mistral, Phi-3) | Local | No | **Yes** |
| **LM Studio** | Local | No | **Yes** |
| **vLLM / LocalAI / text-generation-webui** | Self-hosted | Optional | **Yes** |
| Any OpenAI-compatible endpoint | Varies | Varies | Varies |

---

## Quick Start

### 1. Install the Plugin

**Build from source:**

```bash
git clone https://github.com/forgeai-oss/forgeai-pipeline-intelligence.git
cd forgeai-pipeline-intelligence
mvn clean package -DskipTests
```

Upload `target/forgeai-pipeline-intelligence.hpi` via **Manage Jenkins → Plugins → Advanced → Deploy Plugin**.

**From the Jenkins Update Center** (once published):

Manage Jenkins → Plugins → Available → search for "ForgeAI Pipeline Intelligence"

### 2. Configure the LLM Provider

Navigate to **Manage Jenkins → System → ForgeAI Pipeline Intelligence**:

1. Select your **LLM Provider** (OpenAI / Anthropic / Ollama)
2. Enter the **Endpoint URL** (e.g., `https://api.openai.com/`)
3. Enter the **Model ID** (e.g., `gpt-4o`)
4. Select or create an **API Key credential** (Jenkins Secret Text)
5. Click **Test Connection** to verify
6. Enable or disable individual analyzers
7. Save

### 3. Add to Your Jenkinsfile

**Full suite (recommended):**

```groovy
stage('ForgeAI Intelligence') {
    steps {
        script {
            def report = forgeAI(
                analyzers: ['code-review', 'vulnerability', 'architecture-drift',
                            'test-gaps', 'dependency-risk', 'release-readiness'],
                sourceGlob: 'src/**/*.java',
                contextInfo: 'Spring Boot microservice, hexagonal architecture',
                failOnCritical: true,
                criticalThreshold: 4
            )
            echo "Composite Score: ${report.compositeScore}/10"
        }
    }
    post {
        always {
            archiveArtifacts artifacts: 'forgeai-reports/**', allowEmptyArchive: true
            publishHTML(target: [
                reportDir: 'forgeai-reports',
                reportFiles: 'forgeai-report.html',
                reportName: 'ForgeAI Report'
            ])
        }
    }
}
```

**Single analyzer (targeted):**

```groovy
def result = forgeAIScan(
    analyzer: 'vulnerability',
    source: readFile('src/main/java/App.java'),
    context: 'Java 17 REST API handling PII data'
)
if (result.criticalCount > 0) {
    error("Security scan found ${result.criticalCount} critical vulnerabilities")
}
```

**Parallel analyzers:**

```groovy
stage('ForgeAI Parallel') {
    parallel {
        stage('Security')     { steps { script { forgeAIScan analyzer: 'vulnerability',       source: src } } }
        stage('Architecture') { steps { script { forgeAIScan analyzer: 'architecture-drift',  source: src } } }
        stage('Test Gaps')    { steps { script { forgeAIScan analyzer: 'test-gaps',           source: src } } }
    }
}
```

See the [`examples/`](examples/) directory for complete, annotated Jenkinsfiles.

---

## Pipeline DSL Reference

### `forgeAI` — Full Analysis Step

| Parameter | Type | Default | Description |
|---|---|---|---|
| `analyzers` | `List<String>` | All 7 analyzers | Which analyzers to run |
| `sourceGlob` | `String` | `**/*.java,**/*.py,**/*.js,**/*.ts,**/*.go,**/*.rs` | Glob patterns for source files |
| `contextInfo` | `String` | `""` | Project description, architecture, or constraints |
| `failOnCritical` | `boolean` | `false` | Fail build if composite score falls below threshold |
| `criticalThreshold` | `int` | `3` | Minimum composite score (1–10) |

**Returns** a `Map` with: `compositeScore`, `totalFindings`, `criticalCount`, `analyzerCount`, and per-analyzer scores (e.g., `code-reviewScore`, `vulnerabilityScore`).

### `forgeAIScan` — Single Analyzer Step

| Parameter | Type | Description |
|---|---|---|
| `analyzer` | `String` | Analyzer ID (see table above) |
| `source` | `String` | Source code or diff to analyze |
| `context` | `String` | Additional context |

**Returns** a `Map` with: `score`, `severity`, `summary`, `findingsCount`, `criticalCount`, `highCount`.

---

## Air-Gapped / Local LLM Setup

ForgeAI supports fully offline operation — no data ever leaves your network.

### Ollama

```bash
# Install Ollama
curl -fsSL https://ollama.ai/install.sh | sh

# Pull a code-focused model
ollama pull deepseek-coder:6.7b      # Fast, good for most use cases (~4 GB)
ollama pull deepseek-coder:33b       # More accurate, requires ~20 GB RAM
ollama pull codellama:13b            # Meta's code model
ollama pull llama3:8b                # General-purpose, solid code skills

# Verify it is running
curl http://localhost:11434/api/tags
```

Jenkins global config:
```
Provider:  Ollama (Local)
Endpoint:  http://localhost:11434
Model ID:  deepseek-coder:6.7b
API Key:   (leave blank)
```

### LM Studio

1. Download from [lmstudio.ai](https://lmstudio.ai)
2. Load any GGUF model (e.g., `deepseek-coder-v2`)
3. Start the local server (default: `http://localhost:1234`)
4. In Jenkins:

```
Provider:  OpenAI / OpenAI-Compatible
Endpoint:  http://localhost:1234/
Model ID:  (auto-detected by LM Studio)
API Key:   (leave blank)
```

---

## HTML Report

Every build generates a self-contained HTML report with:

- **Composite score** and **release verdict** (SHIP_IT / CAUTION / HOLD / BLOCK)
- **Per-analyzer breakdown** with individual scores
- **Detailed findings** with severity, file location, and suggested fixes
- **Dark theme** optimised for readability

The report is written to `forgeai-reports/forgeai-report.html` in the workspace. Use `publishHTML` (HTML Publisher plugin) or `archiveArtifacts` to surface it on the build page.

---

## Configuration Reference

### Global Settings (Manage Jenkins → System → ForgeAI Pipeline Intelligence)

| Setting | Description | Default |
|---|---|---|
| LLM Provider | OpenAI / Anthropic / Ollama | OpenAI |
| Endpoint URL | API base URL | `https://api.openai.com/` |
| Model ID | Model to use | `gpt-4o` |
| API Key Credential | Jenkins Secret Text credential ID | — |
| Temperature | LLM creativity (0.0–1.0) | `0.2` |
| Timeout | Request timeout in seconds | `120` |
| Max Tokens | Maximum response length | `4096` |
| Per-analyzer toggles | Enable or disable each analyzer globally | All enabled |
| Publish HTML Report | Generate the HTML report artifact | `true` |
| Fail on Low Score | Fail build below the threshold | `false` |
| Score Threshold | Minimum passing composite score (1–10) | `3` |
| Custom System Prompt | Text prepended to every LLM prompt | — |

---

## Building from Source

**Prerequisites:** JDK 17+, Maven 3.9+

```bash
git clone https://github.com/forgeai-oss/forgeai-pipeline-intelligence.git
cd forgeai-pipeline-intelligence

# Full build with tests
mvn clean verify

# Build only (skip tests)
mvn clean package -DskipTests

# The installable plugin is at:
#   target/forgeai-pipeline-intelligence.hpi
```

> **Note:** The CI workflow tests against Java 17 and Java 21. Java 25 requires additional Maven configuration to disable two parent-POM-bound plugins (`io.jenkins.tools.maven:license-maven-plugin` and `com.github.spotbugs:spotbugs-maven-plugin`) that bundle ASM versions incompatible with class file major version 69. These overrides are already present in `pom.xml`.

See [LOCAL_TESTING.md](LOCAL_TESTING.md) for a complete guide covering local Jenkins setup, LLM provider configuration, and pre-release validation.

---

## What Makes ForgeAI Different

| Feature | ForgeAI | Typical AI Plugins |
|---|---|---|
| Architecture drift detection | Yes — pattern-aware | No — code-level only |
| Composite release scoring | Yes — weighted, cross-analyzer | No — single dimension |
| Pipeline self-optimisation | Yes — analyses the Jenkinsfile itself | Not available |
| Air-gapped local LLM | Yes — Ollama, LM Studio, vLLM | No — cloud-only |
| Multi-provider abstraction | Yes — OpenAI, Anthropic, Ollama, custom | No — single vendor |
| Quality gate with verdicts | Yes — SHIP_IT / CAUTION / HOLD / BLOCK | Pass/fail only |
| Dependency supply-chain risk | Yes — license, maintenance, depth | CVE-only |
| Commit intelligence + changelog | Yes — auto semver + changelog draft | Not available |
| Admin GUI with test connection | Yes — full Jelly config UI | Config-file only |

---

## Project Structure

```
forgeai-pipeline-intelligence/
├── pom.xml
├── src/
│   ├── main/java/io/forgeai/jenkins/
│   │   ├── config/
│   │   │   └── ForgeAIGlobalConfiguration.java   # Admin GUI settings
│   │   ├── llm/
│   │   │   ├── LLMProvider.java                   # Provider interface
│   │   │   ├── OpenAICompatibleProvider.java      # OpenAI / LM Studio / vLLM
│   │   │   ├── AnthropicProvider.java             # Anthropic Claude API
│   │   │   ├── OllamaProvider.java                # Local Ollama
│   │   │   ├── LLMProviderFactory.java
│   │   │   └── LLMException.java
│   │   ├── analyzers/
│   │   │   ├── BaseAnalyzer.java
│   │   │   ├── CodeReviewAnalyzer.java
│   │   │   ├── VulnerabilityAnalyzer.java
│   │   │   ├── ArchitectureDriftAnalyzer.java
│   │   │   ├── TestGapAnalyzer.java
│   │   │   ├── DependencyRiskAnalyzer.java
│   │   │   ├── CommitIntelligenceAnalyzer.java
│   │   │   ├── PipelineAdvisorAnalyzer.java
│   │   │   ├── ReleaseReadinessAnalyzer.java
│   │   │   └── ResultParser.java
│   │   ├── steps/
│   │   │   ├── ForgeAIStep.java                   # forgeAI pipeline step
│   │   │   ├── ForgeAIScanStep.java               # forgeAIScan pipeline step
│   │   │   └── DirectoryTreeCallable.java
│   │   └── reports/
│   │       ├── AnalysisResult.java
│   │       └── ForgeAIReportGenerator.java
│   ├── main/resources/
│   │   ├── index.jelly
│   │   └── io/forgeai/jenkins/
│   │       ├── config/ForgeAIGlobalConfiguration/config.jelly
│   │       └── steps/ForgeAIStep/config.jelly
│   └── test/java/io/forgeai/jenkins/
│       └── ForgeAIPluginTest.java
├── examples/
│   ├── Jenkinsfile.full-suite      # Full analysis with HTML report
│   ├── Jenkinsfile.targeted        # Parallel targeted scans
│   └── Jenkinsfile.local-ollama    # Air-gapped local LLM
├── .github/workflows/ci.yml        # GitHub Actions (Java 17 + 21 matrix)
├── LOCAL_TESTING.md
├── CONTRIBUTING.md
└── LICENSE                         # Apache 2.0
```

---

## Requirements

| Requirement | Minimum |
|---|---|
| Jenkins | 2.426.3 LTS |
| Java (runtime) | 17 |
| Java (build) | 17 (tested through 21) |
| Maven (build) | 3.9 |
| LLM | OpenAI API key, Anthropic API key, or Ollama running locally |

---

## Roadmap

- [ ] GitHub Checks API — post findings as PR annotations
- [ ] SonarQube integration — augment AI analysis with static analysis data
- [ ] Historical trend dashboard — track scores across builds
- [ ] Slack / Teams notifications with score summaries
- [ ] Multi-language prompt tuning — model-specific prompt optimisation
- [ ] Custom analyzer support — define your own analyzer prompts via the UI
- [ ] GitLab CI and GitHub Actions adapters

---

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on reporting bugs, suggesting features, and submitting pull requests.

Key areas where help is particularly valuable:
- Testing with additional LLM providers and models
- Prompt engineering improvements
- Additional language support (Go, Rust, C#, Ruby)
- HTML report UI improvements
- Documentation and tutorials

---

## License

[MIT License](LICENSE)

---

<div align="center">

[Report a Bug](https://github.com/forgeai-oss/forgeai-pipeline-intelligence/issues/new?template=bug_report.md) · [Request a Feature](https://github.com/forgeai-oss/forgeai-pipeline-intelligence/issues/new?template=feature_request.md) · [Contribute](CONTRIBUTING.md)

</div>
