# Open Video Clipper (OVC)

A self-hosted web application that automatically transcribes videos, uses AI to find the most interesting moments, and clips them out — all from a local browser UI.

**Stack:** Java 25 · Quarkus 3.38 · SQLite · Qute templates · Bulma CSS · Ollama (local LLM) · Python (transcription/scenes)

---

## What it does

1. **Upload a video** (or point to a local file on disk)
2. **Transcribe** the audio using a Python-backed provider (Parakeet or Faster-Whisper)
3. **Review the transcription** — edit chunks inline with a karaoke-style synced player
4. **Analyze with AI** — send the transcript to a local Ollama LLM to find highlight moments (funny, exciting, informative, etc.)
5. **Select & adjust clips** — toggle, lock, and fine-tune suggested moments
6. **Export clips** — FFmpeg cuts the selected segments into standalone MP4 files

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Browser (Bulma CSS + HTMX + Vanilla JS)                    │
│  ┌────────────┐ ┌──────────────┐ ┌────────────────────────┐  │
│  │  Setup     │ │  Job Detail  │ │  Suggestions / Results │  │
│  │  (config)  │ │  (SSE live)  │ │  (toggle, lock, clip)  │  │
│  └─────┬──────┘ └──────┬───────┘ └───────────┬────────────┘  │
└────────┼───────────────┼─────────────────────┼───────────────┘
         │               │                     │
         ▼               ▼                     ▼
┌──────────────────────────────────────────────────────────────┐
│  Quarkus REST (OvcController)                                │
│  ─ Qute HTML templates (server-rendered, HTMX fragments)     │
│  ─ SSE endpoint for live transcription streaming             │
│  ─ JSON API for chunk edits, toggles, re-analysis            │
└───────────────────────────┬──────────────────────────────────┘
                            │
         ┌──────────────────┼──────────────────┐
         ▼                  ▼                  ▼
┌─────────────┐   ┌──────────────┐   ┌───────────────────┐
│ VideoService│   │Transcription │   │  VideoClipping    │
│  (orchestr.)│   │   Service    │   │    Service        │
└──────┬──────┘   └──────┬───────┘   └────────┬──────────┘
       │                 │                    │
       ▼                 ▼                    ▼
┌─────────────┐   ┌──────────────┐   ┌───────────────────┐
│ JobExecution│   │  Provider    │   │     FFmpeg        │
│  Manager    │   │  Factory     │   │  (extract audio,  │
│ (thread pool│   │              │   │   cut clips)      │
│  4 workers) │   └──────┬───────┘   └───────────────────┘
└─────────────┘          │
                   ┌─────┴──────┐
                   ▼            ▼
            ┌───────────┐ ┌───────────────┐
            │ Parakeet  │ │Faster-Whisper │
            │ (Python)  │ │  (Python)     │
            └───────────┘ └───────────────┘

                            │
                            ▼
              ┌──────────────────────────┐
              │  Ollama (local LLM)      │
              │  - Highlight extraction  │
              │  - Re-analysis w/ prompt │
              └──────────────────────────┘

              ┌──────────────────────────┐
              │  SQLite (Hibernate ORM)  │
              │  VideoJob, ClipSuggestion│
              │  Clip, TranscriptionChunk│
              └──────────────────────────┘
```

### Processing Pipeline (Job Status Flow)

```
UPLOADED → TRANSCRIBING → TRANSCRIPTION_REVIEW → ANALYZING → SUGGESTIONS_READY → CLIPPING → COMPLETED
                │                    │                 │
                └── (on failure) ────┴───── FAILED ────┘
```

| Status | What's happening |
|---|---|
| `UPLOADED` | Video file registered, ready for processing |
| `TRANSCRIBING` | Audio extracted (FFmpeg), Python transcription running in chunks |
| `TRANSCRIPTION_REVIEW` | Transcription complete — user can edit chunks before AI analysis |
| `ANALYZING` | Transcript sent to Ollama LLM in segments for highlight extraction |
| `SUGGESTIONS_READY` | AI suggestions presented — user selects which to clip |
| `CLIPPING` | FFmpeg cutting selected segments into standalone MP4s |
| `COMPLETED` | All clips generated and ready for download |

### Key Components

| Component | Role |
|---|---|
| `OvcController` | Single JAX-RS resource — all routes, HTMX fragment rendering, SSE streaming |
| `VideoService` | Core orchestrator — upload, pipeline state machine, transcription rebuild |
| `JobExecutionManager` | Fixed thread pool (4 workers), tracks active jobs, progress, and phase labels |
| `TranscriptionService` | Provider-agnostic — delegates to Parakeet or FasterWhisper via factory |
| `LLMProvider` / `LLMProviderFactory` | Calls Ollama REST API to extract highlight suggestions from transcript segments |
| `VideoClippingService` | FFmpeg wrapper — audio extraction, duration probing, clip cutting |
| `TranscriptionChunkBatchService` | Batches chunk DB writes for streaming transcription performance |
| `SettingsService` / `SetupService` | Config persistence (Ollama URL, model, provider, analysis params) |
| `PrerequisiteService` | Startup checks for Python, FFmpeg, torch, transformers, soundfile |

### Python Scripts (`scripts/`)

| Script | Purpose |
|---|---|
| `transcribe_parakeet.py` | Streaming transcription with NVIDIA Parakeet (15s chunks, 5s overlap) |
| `transcribe_faster_whisper.py` | Streaming transcription with Faster-Whisper (CTranslate2, model configurable) |
| `detect_scenes.py` | Scene-cut detection via PySceneDetect (Phase 2 — not yet wired in) |

### Data Model (SQLite)

- **VideoJob** — central entity: file path, status, transcription text, word timestamps, duration
- **TranscriptionChunk** — indexed chunks from streaming transcription (for live display + editing)
- **ClipSuggestion** — AI-generated moment suggestions with time range, title, reason, confidence, locked/selected state
- **Clip** — final generated video clip file reference

---

## Getting Started

### Prerequisites

> **Don't have [Chocolatey](https://chocolatey.org/) yet?** Install it first — run this in an **elevated PowerShell** (Run as Administrator):
>
> ```powershell
> Set-ExecutionPolicy Bypass -Scope Process -Force
> [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor 3072
> iex ((New-Object System.Net.WebClient).DownloadString('https://community.chocolatey.org/install.ps1'))
> ```

| Requirement | Version | Install (Chocolatey) | Link |
|---|---|---|---|
| [Java (JDK)](#java) | 25+ (LTS) | `choco install microsoft-openjdk25` | [adoptium.net](https://adoptium.net/temurin/releases/?version=25) |
| [Apache Maven](#apache-maven) | 3.9+ | `choco install maven` | [maven.apache.org](https://maven.apache.org/install.html) |
| [Python](#python) | 3.10+ | `choco install python` | [python.org](https://www.python.org/downloads/) |
| [FFmpeg](#ffmpeg) | 6+ | `choco install ffmpeg` | [ffmpeg.org](https://ffmpeg.org/download.html) |
| [Ollama](#ollama) | latest | `choco install ollama` | [ollama.com](https://ollama.com/download) |

#### Java

The project targets **Java 25 LTS**. Any OpenJDK 25 distribution works.

```powershell
# Chocolatey (Microsoft OpenJDK 25)
choco install microsoft-openjdk25 -y

# Or Eclipse Temurin
choco install temurin25jdk -y

# Verify
java -version
```

#### Apache Maven

```powershell
choco install maven -y

# Verify
mvn -v
```

#### Python

Used for transcription backends (Parakeet, Faster-Whisper) and scene detection.

```powershell
choco install python -y

# After install, install transcription dependencies
cd scripts
pip install -r requirements.txt
```

The `requirements.txt` covers `torch`, `transformers`, `soundfile`, `faster-whisper`, and `scenedetect`.

#### FFmpeg

Required for audio extraction and video clipping. Must be on PATH.

```powershell
choco install ffmpeg -y

# Verify
ffmpeg -version
```

#### Ollama

Local LLM server for highlight extraction. Runs on `http://localhost:11434` by default.

```powershell
# Chocolatey
choco install ollama -y

# Or PowerShell installer
irm https://ollama.com/install.ps1 | iex
```

After installation, pull a model:

```powershell
ollama pull qwen2.5
```

### Development

```bash
# Start in dev mode (hot reload)
run-dev.bat
# → http://localhost:9100
```

### Build Uber-Jar

```bash
# Build self-contained jar
build-uberjar.bat

# Run
java -jar target/ovc-1.1.0-runner.jar
```

### First Run

1. Open `http://localhost:9100`
2. Setup page checks prerequisites and configures storage path, Ollama URL, model, and transcription provider
3. Upload a video or select a local file
4. Watch the live transcription stream, review/edit, then proceed to AI analysis
5. Select clips and export

---

## Configuration

All settings are stored in `application.properties` with runtime overrides via the Settings page:

| Setting | Default | Description |
|---|---|---|
| `ovc.ollama.url` | `http://localhost:11434` | Ollama server URL |
| `ovc.ollama.model` | `qwen2.5` | LLM model for analysis |
| `ovc.transcription.provider` | `parakeet` | `parakeet` or `faster-whisper` |
| `ovc.transcription.model` | `large-v3` | Whisper model size |
| `ovc.analysis.segment-size` | `3000` | Words per analysis segment |
| `ovc.analysis.overlap-words` | `300` | Overlap between segments |
| `ovc.analysis.max-suggestions` | `15` | Max clip suggestions per video |
| `ovc.clip.codec` | `copy` | FFmpeg codec (`copy` = no re-encode) |
| `ovc.clip.format` | `mp4` | Output container format |

---

## Project Structure

```
OpenVideoClipper/
├── pom.xml                          # Maven config (Quarkus 3.38, Java 25, SQLite, Qute)
├── run-dev.bat                      # Dev server launcher
├── build-uberjar.bat                # Production build script
├── scripts/                         # Python transcription/detection backends
│   ├── transcribe_parakeet.py
│   ├── transcribe_faster_whisper.py
│   ├── detect_scenes.py
│   └── requirements.txt
├── src/main/java/com/openvideoclipper/
│   ├── OvcApplication.java          # Quarkus entry point
│   ├── DatabaseInitializer.java     # SQLite DB setup
│   ├── config/                      # OvcConfig, StorageConfig
│   ├── entity/                      # VideoJob, Clip, ClipSuggestion, etc.
│   ├── repository/                  # Panache repositories
│   ├── rest/OvcController.java      # All HTTP routes + HTMX fragments
│   ├── service/                     # Business logic layer
│   │   ├── VideoService.java        # Core orchestrator
│   │   ├── transcription/           # Provider abstraction (Parakeet, FasterWhisper)
│   │   ├── analysis/                # AnalysisProvider interface + SceneCut
│   │   └── VideoClippingService.java
│   ├── processing/                  # JobExecutionManager, LLMProvider, factories
│   └── utils/                       # StorageUtils, LogUtil
├── src/main/resources/
│   ├── application.properties       # Quarkus + OVC config
│   ├── templates/                   # Qute HTML templates
│   │   ├── base.html                # Layout (Bulma)
│   │   ├── index.html               # Job list
│   │   ├── jobDetail.html           # Live job view (SSE)
│   │   ├── suggestions.html         # Clip selection
│   │   ├── transcription.html       # Transcript + suggestion spans
│   │   ├── results.html             # Generated clips download
│   │   ├── setup.html / settings.html
│   │   └── fragments/               # HTMX partials
│   └── META-INF/resources/          # Static assets (JS, CSS)
│       ├── js/ovc.js, state.js, transcriptionKaraoke.js
│       └── css/ovc.css, bulma.min.css
└── target/                          # Build output (gitignored)
```
