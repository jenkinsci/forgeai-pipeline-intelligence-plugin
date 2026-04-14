# Local Testing Guide — ForgeAI Pipeline Intelligence

This guide walks you through testing ForgeAI on your local machine before publishing.

---

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| JDK | 17+ | `sdk install java 17.0.10-tem` or [Adoptium](https://adoptium.net) |
| Maven | 3.9+ | `sdk install maven` or [Maven](https://maven.apache.org/download.cgi) |
| Git | 2.x | Pre-installed on most systems |
| Docker | 24+ | Optional, for containerized testing |

For **local LLM** testing (no API key needed):
| Tool | Install |
|------|---------|
| Ollama | `curl -fsSL https://ollama.ai/install.sh \| sh` |
| Recommended model | `ollama pull deepseek-coder:6.7b` (fast) or `ollama pull codellama:13b` |

---

## Step 1: Clone and Build

```bash
git clone https://github.com/forgeai-oss/forgeai-pipeline-intelligence.git
cd forgeai-pipeline-intelligence

# Full build with tests
mvn clean verify

# Quick build (skip tests)
mvn clean package -DskipTests
```

The build produces `target/forgeai-pipeline-intelligence.hpi` — this is the installable plugin file.

---

## Step 2: Launch Local Jenkins with the Plugin

### Option A: Maven HPI Plugin (Recommended for Development)

```bash
# Launches Jenkins on http://localhost:8080/jenkins with the plugin pre-installed
mvn hpi:run

# Custom port:
mvn hpi:run -Dport=9090
```

Jenkins starts with an empty configuration. Navigate to **http://localhost:8080/jenkins**.

### Option B: Docker with Plugin Mounted

```bash
# Build the HPI first
mvn clean package -DskipTests

# Run Jenkins in Docker with the plugin
docker run -d \
  --name jenkins-forgeai-test \
  -p 8080:8080 \
  -v $(pwd)/target/forgeai-pipeline-intelligence.hpi:/var/jenkins_home/plugins/forgeai-pipeline-intelligence.hpi \
  jenkins/jenkins:lts-jdk17

# Get the initial admin password
docker exec jenkins-forgeai-test cat /var/jenkins_home/secrets/initialAdminPassword
```

### Option C: Install into Existing Jenkins

1. Go to **Manage Jenkins → Plugins → Advanced Settings**
2. Under **Deploy Plugin**, upload `target/forgeai-pipeline-intelligence.hpi`
3. Restart Jenkins

---

## Step 3: Configure ForgeAI

1. Go to **Manage Jenkins → System** (or Configure System)
2. Scroll to **ForgeAI Pipeline Intelligence**
3. Configure your LLM provider:

### Using Ollama (Local — No API Key)

```
Provider:   Ollama (Local)
Endpoint:   http://localhost:11434
Model ID:   deepseek-coder:6.7b
API Key:    (leave empty)
```

Make sure Ollama is running: `ollama serve`

### Using OpenAI

```
Provider:   OpenAI / OpenAI-Compatible
Endpoint:   https://api.openai.com/
Model ID:   gpt-4o
API Key:    (create a Secret Text credential first)
```

To create the credential:
1. **Manage Jenkins → Credentials → System → Global credentials**
2. **Add Credentials → Secret text**
3. Paste your API key, give it an ID like `openai-api-key`
4. Select it in the ForgeAI dropdown

### Using Anthropic Claude

```
Provider:   Anthropic Claude
Endpoint:   https://api.anthropic.com/
Model ID:   claude-sonnet-4-20250514
API Key:    (create a Secret Text credential)
```

4. Click **Test Connection** to verify
5. Save

---

## Step 4: Create a Test Pipeline Job

1. **New Item → Pipeline**
2. Name it `forgeai-test`
3. In the Pipeline script, paste:

```groovy
pipeline {
    agent any
    stages {
        stage('Test ForgeAI') {
            steps {
                // Write a sample file to analyze
                writeFile file: 'Sample.java', text: '''
                    import java.util.*;
                    public class Sample {
                        private String password = "admin123"; // hardcoded!
                        public void processData(String input) {
                            String query = "SELECT * FROM users WHERE id = " + input; // SQL injection
                            System.out.println(query);
                        }
                        public void unused() { } // dead code
                    }
                '''

                script {
                    def report = forgeAI(
                        analyzers: ['code-review', 'vulnerability'],
                        sourceGlob: '**/*.java'
                    )
                    echo "Score: ${report.compositeScore}"
                    echo "Findings: ${report.totalFindings}"
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'forgeai-reports/**', allowEmptyArchive: true
                }
            }
        }
    }
}
```

4. Click **Build Now**
5. Check the console output for ForgeAI analysis
6. Check **Build Artifacts** for the HTML report

---

## Step 5: Validate the Report

After the build completes:

1. Open the **Console Output** — you should see:
   ```
   ╔══════════════════════════════════════════════════╗
   ║   🔥 ForgeAI Pipeline Intelligence v1.0         ║
   ║   Provider: Ollama Local (deepseek-coder:6.7b)  ║
   ╚══════════════════════════════════════════════════╝
   ```

2. The report should flag:
   - **Hardcoded password** (CRITICAL vulnerability)
   - **SQL injection** (CRITICAL vulnerability)
   - **Dead code** (LOW code-review finding)

3. Download the HTML report from build artifacts and open it in a browser.

---

## Step 6: Run Unit Tests

```bash
# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=ForgeAIPluginTest

# Run with coverage report
mvn verify
# Report at: target/site/jacoco/index.html
```

---

## Step 7: Integration Testing with Jenkins Test Harness

The Jenkins Test Harness provides a real Jenkins instance in-memory:

```bash
# Run integration tests (uses JenkinsRule)
mvn verify -Dtest=ForgeAIIntegrationTest
```

---

## Step 8: Test with Different LLM Providers

Run the same pipeline with different backends to compare results:

| Provider | Model | Expected Behavior |
|----------|-------|-------------------|
| Ollama | `deepseek-coder:6.7b` | Fast, good for code review |
| Ollama | `codellama:13b` | Better accuracy, slower |
| OpenAI | `gpt-4o` | Best accuracy, requires API key |
| OpenAI | `gpt-4o-mini` | Good balance of speed/quality |
| Anthropic | `claude-sonnet-4-20250514` | Excellent code analysis |
| LM Studio | Any GGUF model | Local, use OpenAI-compatible endpoint |

---

## Troubleshooting

### "Connection test failed"
- Verify the endpoint URL (include trailing `/` for OpenAI)
- Check Ollama is running: `curl http://localhost:11434/api/tags`
- Verify API key credential is a **Secret text** type

### "LLM responded but JSON parsing failed"
- Some smaller models produce inconsistent JSON. Try a larger model.
- Check console output for the raw response.

### "Source collection truncated"
- ForgeAI limits source to ~200KB to fit model context. Use `sourceGlob` to target specific files.

### Build timeout
- Increase `timeoutSeconds` in global config (default: 120s)
- Local models (Ollama) may need 180-300s for large codebases

---

## Pre-Publishing Checklist

Before pushing to GitHub and submitting to the Jenkins plugin registry:

- [ ] `mvn clean verify` passes with zero test failures
- [ ] Tested with at least 2 different LLM providers
- [ ] HTML report generates correctly and opens in browser
- [ ] Global configuration saves and loads correctly after Jenkins restart
- [ ] Plugin works on Jenkins LTS (2.426.3+)
- [ ] `Test Connection` button works in admin UI
- [ ] Pipeline Snippet Generator shows ForgeAI steps correctly
- [ ] No hardcoded API keys or secrets in the codebase
- [ ] LICENSE file is present (Apache 2.0)
- [ ] README.md is comprehensive
- [ ] CONTRIBUTING.md is present
