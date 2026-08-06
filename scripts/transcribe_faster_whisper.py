#!/usr/bin/env python3
"""
Transcribe audio using faster-whisper (CTranslate2 optimized Whisper).
Supports streaming mode for real-time transcription with resume capability.

Usage (standard):  python transcribe_faster_whisper.py <audio_file.wav>
Usage (streaming): python transcribe_faster_whisper.py <audio_file.wav> --stream-chunks [--start-chunk N]

Outputs PROGRESS:x lines during processing, then final JSON to stdout.
In streaming mode, outputs one JSON line per chunk for real-time processing.
"""

import json
import sys
import argparse
import warnings
import os
import collections
import tempfile
import concurrent.futures
import torch

warnings.filterwarnings("ignore")

DEFAULT_WORKERS = min(4, os.cpu_count() or 1)


def get_device_and_compute():
    """Auto-detect best available device across platforms for faster-whisper."""
    if torch.cuda.is_available():
        return "cuda", "float16"
    if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
        return "cpu", "int8"
    return "cpu", "int8"


def split_audio_chunks(audio_data, sample_rate, chunk_length_s=15, stride_length_s=5):
    """Split audio into overlapping chunks."""
    chunk_samples = int(chunk_length_s * sample_rate)
    stride_samples = int(stride_length_s * sample_rate)
    total_samples = len(audio_data)

    chunks = []
    start = 0
    chunk_idx = 0

    while start < total_samples:
        end = min(start + chunk_samples, total_samples)
        chunk = audio_data[start:end]
        chunks.append({
            'data': chunk,
            'start_time': start / sample_rate,
            'end_time': end / sample_rate,
            'index': chunk_idx
        })
        start += stride_samples
        chunk_idx += 1

    return chunks


def generate_word_timestamps(chunks):
    """Generate word-level timestamps by proportional allocation within chunks."""
    all_text = " ".join(c['text'] for c in chunks if c.get('text'))
    words = all_text.split()
    word_timestamps = []

    if not words:
        return word_timestamps

    word_idx = 0
    total_words = len(words)

    for chunk in chunks:
        chunk_start = chunk.get('start_time', chunk.get('start', 0))
        chunk_end = chunk.get('end_time', chunk.get('end', 0))
        chunk_duration = chunk_end - chunk_start
        chunk_text = chunk.get('text', '').strip()
        chunk_words = chunk_text.split()
        chunk_word_count = len(chunk_words)

        if chunk_word_count == 0:
            continue

        time_per_word = chunk_duration / chunk_word_count

        for j, word in enumerate(chunk_words):
            word_start = chunk_start + (j * time_per_word)
            word_end = chunk_start + ((j + 1) * time_per_word)
            word_timestamps.append({
                "word": word,
                "start": round(word_start, 3),
                "end": round(word_end, 3)
            })
            word_idx += 1

            if word_idx >= total_words:
                break

    return word_timestamps


def extract_word_timestamps(segments, time_offset=0.0, after_time=0.0):
    """Extract the model's real word-level timestamps from faster-whisper segments.

    faster-whisper reports per-word start/end times (seconds) when
    word_timestamps=True. In streaming mode each chunk is transcribed as its own
    file starting at 0s, so time_offset (the chunk's position in the full audio)
    is added to every word. Words that begin before after_time are treated as
    duplicates from an overlapping chunk and skipped.

    Returns a list of {"word", "start", "end"} dicts.
    """
    timestamps = []
    last_end = after_time
    for segment in segments:
        for word in (segment.words or []):
            start = time_offset + word.start
            end = time_offset + word.end
            if start < last_end - 0.05:
                continue
            text = word.word.strip()
            if not text:
                continue
            timestamps.append({
                "word": text,
                "start": round(start, 3),
                "end": round(end, 3)
            })
            last_end = max(last_end, end)
    return timestamps


def run_streaming_transcription(audio_file, model_size="large-v3", start_chunk=0, chunk_length_s=15, stride_length_s=5, progress_callback=None, workers=DEFAULT_WORKERS):
    """Run transcription in streaming mode, yielding chunk results."""
    import soundfile as sf
    from faster_whisper import WhisperModel

    audio_data, sample_rate = sf.read(audio_file)
    total_seconds = len(audio_data) / sample_rate

    device, compute_type = get_device_and_compute()

    chunks = split_audio_chunks(audio_data, sample_rate, chunk_length_s, stride_length_s)
    total_chunks = len(chunks)

    model = WhisperModel(model_size, device=device, compute_type=compute_type)

    all_chunks = []
    all_word_timestamps = []
    last_word_end = 0.0
    pending = collections.deque()

    def transcribe_chunk(chunk_info):
        # Write chunk to temp file
        with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as tmp:
            sf.write(tmp.name, chunk_info['data'], sample_rate)
            tmp_path = tmp.name

        try:
            segments, info = model.transcribe(tmp_path, word_timestamps=True, vad_filter=True)

            # Materialize: the segments object is a generator and can only be iterated once
            segment_list = list(segments)

            text = " ".join(s.text for s in segment_list).strip()
            return text, segment_list
        finally:
            try:
                os.unlink(tmp_path)
            except:
                pass

    completed = 0

    def collect(idx, fut):
        nonlocal completed, last_word_end
        text, segment_list = fut.result()

        chunk_words = extract_word_timestamps(
            segment_list,
            time_offset=chunks[idx]['start_time'],
            after_time=last_word_end
        )
        all_word_timestamps.extend(chunk_words)
        if chunk_words:
            last_word_end = chunk_words[-1]["end"]

        chunk_info = chunks[idx]
        chunk_result = {
            "index": idx,
            "text": text,
            "start": round(chunk_info['start_time'], 3),
            "end": round(chunk_info['end_time'], 3)
        }
        all_chunks.append(chunk_result)
        completed += 1
        if progress_callback:
            pct = int((completed / total_chunks) * 100)
            progress_callback(pct, idx, total_chunks)
        return {
            "type": "chunk",
            "index": idx,
            "text": text,
            "start": round(chunk_info['start_time'], 3),
            "end": round(chunk_info['end_time'], 3)
        }

    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        for i in range(start_chunk, total_chunks):
            pending.append((i, executor.submit(transcribe_chunk, chunks[i])))
            if len(pending) == workers:
                yield collect(*pending.popleft())
        while pending:
            yield collect(*pending.popleft())

    # Generate final result
    full_text = " ".join(c['text'] for c in all_chunks if c['text'])
    word_timestamps = all_word_timestamps if all_word_timestamps else generate_word_timestamps(all_chunks)
    duration = all_chunks[-1]['end'] if all_chunks else 0

    yield {
        "type": "complete",
        "total_chunks": total_chunks,
        "duration": round(duration, 3),
        "text": full_text,
        "words": word_timestamps
    }


def run_standard_transcription(audio_file, model_size="large-v3", progress_callback=None):
    """Run transcription in standard (non-streaming) mode."""
    import soundfile as sf
    from faster_whisper import WhisperModel

    audio_data, sample_rate = sf.read(audio_file)
    total_seconds = len(audio_data) / sample_rate

    device, compute_type = get_device_and_compute()

    model = WhisperModel(model_size, device=device, compute_type=compute_type)

    segments, info = model.transcribe(audio_file, word_timestamps=True, vad_filter=True)

    segment_list = list(segments)

    all_chunks = []
    for segment in segment_list:
        all_chunks.append({
            "text": segment.text,
            "start": round(segment.start, 3),
            "end": round(segment.end, 3)
        })

    full_text = " ".join(c['text'] for c in all_chunks)
    word_timestamps = extract_word_timestamps(segment_list)
    if not word_timestamps:
        word_timestamps = generate_word_timestamps(all_chunks)
    duration = all_chunks[-1]['end'] if all_chunks else 0

    output = {
        "text": full_text,
        "chunks": all_chunks,
        "words": word_timestamps
    }

    print(f"PROGRESS:100", flush=True)
    print(json.dumps(output))


def main():
    parser = argparse.ArgumentParser(description="Transcribe audio with faster-whisper")
    parser.add_argument("audio_file", nargs="?", help="Path to audio file")
    parser.add_argument("--model", type=str, default="large-v3", help="Model size (tiny, base, small, medium, large-v3)")
    parser.add_argument("--stream-chunks", action="store_true", help="Enable streaming mode (one JSON line per chunk)")
    parser.add_argument("--start-chunk", type=int, default=0, help="Chunk index to start from (for resume)")
    parser.add_argument("--chunk-length", type=int, default=15, help="Chunk length in seconds")
    parser.add_argument("--stride", type=int, default=5, help="Stride/overlap in seconds")
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS, help="Number of parallel chunk workers")

    args = parser.parse_args()

    if not args.audio_file:
        print(json.dumps({"error": "No audio file provided"}), file=sys.stderr)
        sys.exit(1)

    if args.stream_chunks:
        def progress_cb(pct, idx, total):
            print(json.dumps({"type": "progress", "chunk": idx, "total_chunks": total, "pct": pct}), flush=True)

        try:
            for result in run_streaming_transcription(
                args.audio_file,
                model_size=args.model,
                start_chunk=args.start_chunk,
                chunk_length_s=args.chunk_length,
                stride_length_s=args.stride,
                workers=args.workers,
                progress_callback=progress_cb
            ):
                print(json.dumps(result), flush=True)
        except Exception as e:
            import traceback
            print(json.dumps({"error": str(e), "traceback": traceback.format_exc()}), file=sys.stderr)
            sys.exit(1)
    else:
        run_standard_transcription(args.audio_file, model_size=args.model)


if __name__ == "__main__":
    main()