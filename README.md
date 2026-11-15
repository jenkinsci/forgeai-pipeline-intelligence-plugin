# ForgeAI Pipeline Intelligence

### AI-Powered Code Reviews, Security Analysis, Architecture Drift Detection & Release Readiness — Directly in Your Jenkins Pipeline

[![CI](https://github.com/jenkinsci/forgeai-pipeline-intelligence-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/jenkinsci/forgeai-pipeline-intelligence-plugin/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Jenkins Plugin](https://img.shields.io/badge/Jenkins-2.528.3%2B-blue.svg)](https://www.jenkins.io/)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net)

---

## Table of Contents

- [Why ForgeAI?](#why-forgeai)
- [Analyzers](#analyzers)
- [Supported LLM Backends](#supported-llm-backends)
- [Quick Start](#quick-start)
- [Pipeline DSL Reference](#pipeline-dsl-reference)
- [Air-Gapped / Local LLM Setup](#air-gapped--local-llm-setup)
- [Build Page and Score Trend](#build-page-and-score-trend)
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

> **Just want to see it work?** [`demo/`](demo) starts Jenkins with ForgeAI and a local Ollama model in
> one `docker compose up`, with a sample job ready to run. No API key, nothing leaves your machine.

### 1. Install the Plugin

**From the Jenkins Update Center (recommended):**

Manage Jenkins → Plugins → Available Plugins → search **"ForgeAI Pipeline Intelligence"** → Install

**Build from source:**

```bash
git clone https://github.com/jenkinsci/forgeai-pipeline-intelligence-plugin.git
cd forgeai-pipeline-intelligence-plugin
mvn clean package -DskipTests
```

Upload `target/forgeai-pipeline-intelligence.hpi` via **Manage Jenkins → Plugins → Advanced → Deploy Plugin**.

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
| `scope` | `String` | `auto` | `auto`, `changes` or `all`: which files the code analyzers read (see below) |
| `baseRef` | `String` | PR target | Branch, tag or commit to compare against when analyzing changes |
| `failOnCritical` | `boolean` | Global setting | Fail build if composite score falls below threshold, or if an analyzer fails |
| `criticalThreshold` | `int` | Global setting | Minimum composite score (1–10) |

**Returns** a `Map` with: `compositeScore`, `totalFindings`, `criticalCount`, `analyzerCount`, `failedAnalyzers` (IDs of analyzers that errored or returned unparseable output), `analysisScope` (`changes` or `all`), `filesAnalyzed`, `filesSkipped`, `sarifFile`, and per-analyzer scores (e.g., `code-reviewScore`, `vulnerabilityScore`).

#### What gets analyzed

On a pull request build, the code analyzers read only the files the pull request changed. `scope: 'auto'` (the default) detects this from `CHANGE_TARGET`, which multibranch pipelines set, and compares against that target branch. On other builds it analyzes every file matching `sourceGlob`.

* `scope: 'changes'` always analyzes only changed files. The base is `baseRef` if you set it, else the pull request target, else the previous build's commit (`GIT_PREVIOUS_SUCCESSFUL_COMMIT`).
* `scope: 'all'` always analyzes every matching file.

The base branch must exist in the workspace's git repository. If ForgeAI cannot find it, it logs a warning and analyzes all files. When no source file changed, the code analyzers are skipped and make no LLM calls.

```groovy
// Compare a branch build against main
forgeAI scope: 'changes', baseRef: 'origin/main'
```

Files are sent whole. One LLM request holds 60,000 characters; larger inputs are split across several requests per analyzer (up to *Max Source Chunks per Analyzer*, default 4) and the results are merged: the score is the average, the severity is the worst, and all findings are kept. Files beyond that are skipped and listed in the build log.

`commit-intel` analyzes the commits and diff since the base when one is known, otherwise the last 20 commits and the HEAD diff. It is skipped when the workspace has no git history.

When the quality gate is enabled, the build also fails if any requested analyzer errors (for example, an unreachable LLM) or returns output that cannot be parsed — a gate that could not evaluate the code does not pass.

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

> ForgeAI sends LLM requests from the **Jenkins controller**, not from the agent running the build. The endpoint must be reachable from the controller; `localhost` means the controller's own machine.

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

## Build Page and Score Trend

Each build that runs `forgeAI` gets a **ForgeAI Analysis** page, linked from the build's sidebar and summarized on the build page. It shows the composite score, the verdict, each analyzer's score and summary, and every finding with its location and suggested fix. Jenkins renders the page itself, so it needs no extra plugin and follows the Jenkins theme.

The job page shows a **ForgeAI score trend**: the composite score of the last 30 builds that ran ForgeAI, with the quality-gate threshold when the gate is on. Select a point to open that build's analysis, or **ForgeAI Trend** in the sidebar for a table of the same builds.

* A build that fails the quality gate keeps its analysis, so you can see why it failed.
* When a build runs `forgeAI` more than once, the page shows the last run.
* The page stores scores and findings with the build. The raw model responses are left out; they are in the HTML report.

---

## HTML Report

Every build generates a self-contained HTML report with:

- **Composite score** and **release verdict** (SHIP_IT / CAUTION / HOLD / BLOCK)
- **Per-analyzer breakdown** with individual scores
- **Detailed findings** with severity, file location, and suggested fixes
- **Dark theme** optimised for readability

The report is written to `forgeai-reports/forgeai-report.html` in the workspace. Use `publishHTML` (HTML Publisher plugin) or `archiveArtifacts` to surface it on the build page.

### SARIF for Warnings NG and code scanning

Every run also writes `forgeai-reports/forgeai-report.sarif`, the findings in [SARIF 2.1.0](https://sarifweb.azurewebsites.net/), the standard format for static-analysis results. The step returns its path as `sarifFile`.

With the [Warnings NG plugin](https://plugins.jenkins.io/warnings-ng/), findings appear on the build page with source annotations, trend charts and Warnings NG's own quality gates:

```groovy
def report = forgeAI(analyzers: ['code-review', 'vulnerability'])
recordIssues tool: sarif(pattern: report.sarifFile, id: 'forgeai', name: 'ForgeAI')
```

The same file can be uploaded to GitHub code scanning or any other SARIF consumer.

* Each analyzer is a rule (`forgeai/code-review`, `forgeai/vulnerability`, ...).
* CRITICAL and HIGH findings are reported as `error`, MEDIUM as `warning`, LOW and INFO as `note`.
* Only findings with a file location are included, because SARIF consumers anchor results to source. Findings without one, such as release-readiness verdicts, stay in the HTML report. Pipeline advisor findings point at the `Jenkinsfile`.

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
| Max Source Chunks per Analyzer | LLM requests one analyzer may use when the source exceeds one request | `4` |
| Parallel LLM Requests | Analyzers that call the LLM at once; `0` = automatic (4 for cloud providers, 1 for Ollama) | `0` |
| Per-analyzer toggles | Enable or disable each analyzer globally | All enabled |
| Publish HTML Report | Generate the HTML report artifact | `true` |
| Fail on Low Score | Fail build below the threshold (step `failOnCritical` overrides) | `false` |
| Score Threshold | Minimum passing composite score (1–10; step `criticalThreshold` overrides) | `3` |
| Custom System Prompt | Text prepended to every LLM prompt | — |

LLM requests use the proxy configured in **Manage Jenkins → System → HTTP Proxy Configuration**, including its no-proxy hosts and proxy credentials.

---

## Building from Source

**Prerequisites:** JDK 17+, Maven 3.9+

```bash
git clone https://github.com/jenkinsci/forgeai-pipeline-intelligence-plugin.git
cd forgeai-pipeline-intelligence-plugin

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
│   │   │   ├── WorkspaceInputs.java               # Sources, manifests, git history, change scope
│   │   │   └── DirectoryTreeCallable.java
│   │   ├── actions/
│   │   │   ├── ForgeAIBuildAction.java            # ForgeAI Analysis page of a build
│   │   │   └── ForgeAIJobAction.java              # Score trend on the job page
│   │   └── reports/
│   │       ├── AnalysisResult.java
│   │       ├── ForgeAIReportGenerator.java        # HTML report
│   │       └── ForgeAISarifGenerator.java         # SARIF 2.1.0
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
└── LICENSE                         # MIT
```

---

## Requirements

| Requirement | Minimum |
|---|---|
| Jenkins | 2.528.3 LTS |
| Java (runtime) | 17 |
| Java (build) | 17 (tested through 21) |
| Maven (build) | 3.9 |
| LLM | OpenAI API key, Anthropic API key, or Ollama running locally |

---

## Roadmap

- [ ] GitHub Checks API — post findings as PR annotations
- [ ] SonarQube integration — augment AI analysis with static analysis data
- [x] Score trend across builds, on the job page
- [x] SARIF output for Warnings NG and GitHub code scanning
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

[Report a Bug](https://github.com/jenkinsci/forgeai-pipeline-intelligence-plugin/issues/new?template=bug_report.md) · [Request a Feature](https://github.com/jenkinsci/forgeai-pipeline-intelligence-plugin/issues/new?template=feature_request.md) · [Contribute](CONTRIBUTING.md)

</div>
