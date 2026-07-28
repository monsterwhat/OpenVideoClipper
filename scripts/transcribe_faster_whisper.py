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
import torch

warnings.filterwarnings("ignore")


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
        chunk_start = chunk['start_time']
        chunk_end = chunk['end_time']
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


def run_streaming_transcription(audio_file, model_size="large-v3", start_chunk=0, chunk_length_s=15, stride_length_s=5, progress_callback=None):
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

    for i in range(start_chunk, total_chunks):
        chunk_info = chunks[i]

        if progress_callback:
            pct = int((i / total_chunks) * 100)
            progress_callback(pct, i, total_chunks)

        # Write chunk to temp file
        import tempfile
        with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as tmp:
            sf.write(tmp.name, chunk_info['data'], sample_rate)
            tmp_path = tmp.name

        try:
            segments, info = model.transcribe(tmp_path, word_timestamps=True)
            
            segment_texts = []
            for segment in segments:
                segment_texts.append(segment.text)
            
            text = " ".join(segment_texts).strip()

            chunk_result = {
                "index": i,
                "text": text,
                "start": round(chunk_info['start_time'], 3),
                "end": round(chunk_info['end_time'], 3)
            }
            all_chunks.append(chunk_result)

            yield {
                "type": "chunk",
                "index": i,
                "text": text,
                "start": round(chunk_info['start_time'], 3),
                "end": round(chunk_info['end_time'], 3)
            }

        finally:
            import os
            try:
                os.unlink(tmp_path)
            except:
                pass

    # Generate final result
    full_text = " ".join(c['text'] for c in all_chunks if c['text'])
    word_timestamps = generate_word_timestamps(all_chunks)
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

    segments, info = model.transcribe(audio_file, word_timestamps=True)
    
    all_chunks = []
    for segment in segments:
        all_chunks.append({
            "text": segment.text,
            "start": round(segment.start, 3),
            "end": round(segment.end, 3)
        })
    
    full_text = " ".join(c['text'] for c in all_chunks)
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