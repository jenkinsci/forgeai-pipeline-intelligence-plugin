# ForgeAI quickstart

Try ForgeAI in a few minutes with Docker. This starts a Jenkins with the plugin installed, configures
it to use a local [Ollama](https://ollama.com) model, and creates a job that analyzes a small sample
service. Nothing leaves your machine and you need no API key.

## Run it

```bash
cd demo
docker compose up -d --build
```

The first start downloads Jenkins, Ollama and the `qwen2.5-coder:3b` model (about 2 GB in total).
When it's ready:

1. Open http://localhost:8080 and sign in as `admin` with password `forgeai`.
2. Open the **forgeai-demo** job and click **Build Now**.
3. When the build finishes, open **ForgeAI Analysis** on the build page for the scores, verdict and
   findings, and **ForgeAI Warnings** for the findings shown against the source code. After a few
   builds, the job page shows the score trend.

On CPU, a full run of seven analyzers takes about four minutes.

## What it shows

The job copies [`sample-app/`](sample-app), a small Flask payments service, into the workspace,
commits it so `commit-intel` has history to read, and runs `forgeAI` with seven analyzers. It then
passes the SARIF file that ForgeAI writes to the
[Warnings NG](https://plugins.jenkins.io/warnings-ng/) plugin, which lists each finding with its file
and line. The sample has real problems for ForgeAI to find:

- SQL built by string concatenation, and a shell command built from a URL parameter
- a hard-coded admin token, and refunds with no amount validation
- discount logic with an untested stacking rule and a single happy-path test
- outdated, unpinned dependencies
- a Jenkinsfile with no timeout or retry

The default 3B model keeps the download small. It finds the main problems but tends to under-rate
their severity and give approximate line numbers; larger models do better. This demo shows the
workflow, not the ceiling.

## Configuration as code

[`jenkins/casc.yaml`](jenkins/casc.yaml) configures ForgeAI with the
[Configuration as Code](https://plugins.jenkins.io/configuration-as-code/) plugin:

```yaml
unclassified:
  forgeAI:
    provider:
      ollama:
        endpoint: "http://ollama:11434"
        modelId: "qwen2.5-coder:3b"
    timeoutSeconds: 300
```

The same block works on your own Jenkins. To use a cloud model instead, replace `ollama` with
`openai` or `anthropic` and set `apiKeyCredentialId` to a Secret Text credential.

## Options

| Variable | Default | Purpose |
|---|---|---|
| `OLLAMA_MODEL` | `qwen2.5-coder:3b` | Any model from the Ollama library, e.g. `qwen2.5-coder:7b` for better results |
| `JENKINS_ADMIN_PASSWORD` | `forgeai` | Password for the `admin` user |
| `JENKINS_PORT` | `8080` | Local port for Jenkins, if 8080 is taken |

```bash
OLLAMA_MODEL=qwen2.5-coder:7b docker compose up -d --build
```

## Notes

- Jenkins listens on `127.0.0.1` only. This setup is for trying ForgeAI, not for production.
- The sample's outdated dependencies are in `sample-app/python-deps.txt`, not `requirements.txt`, so
  that dependency scanners watching this repository don't report them. The job renames the file
  before the analysis.
- The report uses an inline stylesheet, which Jenkins' default Content-Security-Policy blocks for
  archived files. [`report-csp.groovy`](jenkins/init.groovy.d/report-csp.groovy) allows inline styles
  (scripts stay blocked) so the report renders.
- Stop with `docker compose down`. Add `-v` to also delete the downloaded model and Jenkins data.
