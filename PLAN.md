# Project Plan: Enhanced Video Clipping & Analysis

## Current Status
- [x] Fix transcription bugs (Transformers `char_offsets` bug and OOM prevention in `transcribe_parakeet.py`)
- [x] Implement Transcription Review step (User can edit transcription before analysis)
- [ ] Implement Enhanced Analysis Pipeline (Multi-step: Audio Events, Speakers, Scenes, Semantic)
- [x] Support Multi-Provider Transcription (Faster-Whisper, etc.)
- [ ] Implement Moment Scoring (Combining audio/semantic signals with LLM)

---

## Detailed Roadmap

### Phase 1: Transcription Review (The "Middle Step") ✅ DONE
*Goal: Allow users to correct transcription errors before wasting expensive AI resources on analysis.*
- [x] **Backend**:
    - Update `JobStatus` to include `TRANSCRIPTION_REVIEW`.
    - Update `VideoService.continueProcessingImpl` to transition to `TRANSCRIPTION_REVIEW` after transcription.
    - Implement `VideoService.updateTranscription(jobId, text)` to save edited text and transition to `ANALYZING`.
    - Implement `OvcController.updateTranscription` endpoint.
    - Implement `VideoService.updateTranscriptionChunk` for inline chunk editing.
    - Implement `OvcController.updateTranscriptionChunk` endpoint.
- [x] **Frontend**:
    - Created split-screen live transcription fragment (`liveTranscriptionFragment.html`) with video playback (left) and editable transcript chunks (right).
    - Click-to-edit chunks with inline save/cancel.
    - Karaoke-style word highlighting synced to video playback (`transcriptionKaraoke.js`).
    - Real-time streaming chunk updates during `TRANSCRIBING` status.
    - "Proceed to Review" button appears when transcription completes, submits concatenated chunks to `/transcription-review`.

### Phase 2: Enhanced Analysis Pipeline
*Goal: Moving from just LLM highlights to a multi-modal understanding of the video.*
- [ ] **Core Architecture**:
    - Create `AnalysisService` to orchestrate multiple providers.
    - Define `AnalysisProvider` interface and `AnalysisResult` structures.
    - Update `JobStatus` to reflect analysis steps (e.g., `ANALYZING_AUDIO`, `ANALYZING_SCENES`, etc.).
- [ ] **Providers to Implement**:
    - [ ] `SceneCutAnalysisProvider` (PySceneDetect)
    - [ ] `AudioEventAnalysisProvider` (YAMNet/PANNs: cheering, laughter, etc.)
    - [ ] `SpeakerDiarizationProvider` (pyannote.audio)
    - [ ] `SemanticAudioProvider` (CLAP: semantic search)
- [ ] **Database**:
    - Extend `VideoJob` to store complex analysis results (events, speaker segments, scene cuts).

### Phase 3: Multi-Provider Transcription Support ✅ DONE
*Goal: Provide faster and more robust transcription options.*
- [x] **Implement `FasterWhisperTranscriptionProvider`**:
    - Created `transcribe_faster_whisper.py` with streaming mode (15s chunks, 5s overlap) and resume capability.
    - Added `FasterWhisperTranscriptionProvider.java` with chunk callback for real-time persistence.
    - Uses `faster-whisper` library (CTranslate2) for high performance.
    - Supports model selection via config (default: large-v3).
- [x] **Update Config**:
    - Provider dropdown in `setup.html` automatically shows both `parakeet` and `faster-whisper` via `TranscriptionService.getAllProviders()`.

### Phase 4: Moment Scoring & Intelligent Highlights
*Goal: Use all gathered data to find the "best" moments.*
- [ ] **Scoring Engine**:
    - Combine LLM confidence with audio/semantic signals.
    - Implement scoring for: Cheering, Laughter, Excitement, Volume Spikes, Keyword Matches.
- [ ] **Highlight Refinement**:
    - Adjust clip boundaries based on speaker/scene boundaries.

### Phase 5: UI/UX Completion
- [ ] **Advanced Timeline**: Show transcription, speaker labels, and events on a single timeline.
- [ ] **Analysis Progress**: Granular progress updates for each analysis step.
- [ ] **Moment Explorer**: Filterable view of scored highlights.
