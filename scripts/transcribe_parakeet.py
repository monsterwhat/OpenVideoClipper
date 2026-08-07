#!/usr/bin/env python3
"""
Transcribe audio using NVIDIA Parakeet TDT 0.6B v3 via HuggingFace transformers.
Supports streaming mode for real-time transcription with resume capability.

Usage (standard):  python transcribe_parakeet.py <audio_file.wav>
Usage (streaming): python transcribe_parakeet.py <audio_file.wav> --stream-chunks [--start-chunk N]

Outputs PROGRESS:x lines during processing, then final JSON to stdout.
In streaming mode, outputs one JSON line per chunk for real-time processing.
"""

import json
import math
import sys
import threading
import time
import warnings
import argparse
import tempfile
import os
import collections
import concurrent.futures

import torch

warnings.filterwarnings("ignore")

DEFAULT_MODEL = "nvidia/parakeet-tdt-0.6b-v3"
DEFAULT_WORKERS = min(4, os.cpu_count() or 1)


def get_device():
    """Auto-detect best available device across platforms."""
    if torch.cuda.is_available():
        return 0, torch.float16
    if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
        return "mps", torch.float32
    return -1, torch.float32


def split_audio_chunks(audio_data, sample_rate, chunk_length_s=30, stride_length_s=5):
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
        
        # If remaining is very small, just take the rest in next iteration
        if total_samples - start < stride_samples and start < total_samples:
            pass
    
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
        chunk_start = chunk.get('start', chunk.get('start_time', 0))
        chunk_end = chunk.get('end', chunk.get('end_time', 0))
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


def run_streaming_transcription(audio_file, model=DEFAULT_MODEL, start_chunk=0, chunk_length_s=30, stride_length_s=5, progress_callback=None, workers=DEFAULT_WORKERS):
    """Run transcription in streaming mode, yielding chunk results."""
    import soundfile as sf
    from transformers import pipeline
    
    audio_data, sample_rate = sf.read(audio_file)
    total_seconds = len(audio_data) / sample_rate
    
    device, dtype = get_device()
    
    chunks = split_audio_chunks(audio_data, sample_rate, chunk_length_s, stride_length_s)
    total_chunks = len(chunks)
    
    # One pipeline per worker thread. A single shared pipeline is NOT safe here:
    # Parakeet's generate() keeps mutable per-instance state (_step_durations /
    # _encoder_finished) and deletes it when the call returns, so concurrent calls
    # on the same model race and crash with:
    #   AttributeError: 'ParakeetForTDT' object has no attribute '_step_durations'
    # Each worker thread lazily creates its own model copy instead.
    pipe_local = threading.local()

    def get_pipe():
        pipe = getattr(pipe_local, "pipe", None)
        if pipe is None:
            pipe_local.pipe = pipeline(
                "automatic-speech-recognition",
                model=model,
                device=device,
                dtype=dtype,
            )
        return pipe_local.pipe
    
    all_chunks = []
    pending = collections.deque()
    
    def transcribe_chunk(chunk_info):
        # Write chunk to temp file
        with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as tmp:
            sf.write(tmp.name, chunk_info['data'], sample_rate)
            tmp_path = tmp.name
        
        try:
            # Run inference WITHOUT timestamps to avoid tokenizer bug
            result = get_pipe()(tmp_path, return_timestamps=False)
            text = result.get("text", "").strip()
            return {
                "index": chunk_info['index'],
                "text": text,
                "start": round(chunk_info['start_time'], 3),
                "end": round(chunk_info['end_time'], 3)
            }
        finally:
            try:
                os.unlink(tmp_path)
            except:
                pass
    
    completed = 0
    
    def collect(idx, fut):
        nonlocal completed
        chunk_result = fut.result()
        all_chunks.append(chunk_result)
        completed += 1
        if progress_callback:
            pct = int((completed / total_chunks) * 100)
            progress_callback(pct, idx, total_chunks)
        return {
            "type": "chunk",
            "index": chunk_result["index"],
            "text": chunk_result["text"],
            "start": chunk_result["start"],
            "end": chunk_result["end"]
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
    word_timestamps = generate_word_timestamps(all_chunks)
    duration = all_chunks[-1]['end'] if all_chunks else 0
    
    yield {
        "type": "complete",
        "total_chunks": total_chunks,
        "duration": round(duration, 3),
        "text": full_text,
        "words": word_timestamps
    }


def run_standard_transcription(audio_file, model=DEFAULT_MODEL, progress_callback=None):
    """Run transcription in standard (non-streaming) mode."""
    import soundfile as sf
    from transformers import pipeline
    
    audio_data, sample_rate = sf.read(audio_file)
    total_seconds = len(audio_data) / sample_rate
    
    device, dtype = get_device()
    has_cuda = torch.cuda.is_available()
    
    est_sec_per_second = 0.15 if has_cuda else 0.8
    estimated_total_sec = max(5, total_seconds * est_sec_per_second)
    
    start_time = time.time()
    done = False
    lock = threading.Lock()
    
    def report_progress():
        nonlocal done
        while True:
            with lock:
                if done:
                    return
            elapsed = time.time() - start_time
            pct = min(99, int(elapsed / estimated_total_sec * 100))
            if progress_callback:
                progress_callback(pct)
            print(f"PROGRESS:{pct}", flush=True)
            time.sleep(2)
    
    t = threading.Thread(target=report_progress, daemon=True)
    t.start()
    
    try:
        pipe = pipeline(
            "automatic-speech-recognition",
            model=model,
            device=device,
            dtype=dtype,
        )
        
        chunks = split_audio_chunks(audio_data, sample_rate)
        all_chunks = []
        
        for i, chunk_info in enumerate(chunks):
            with lock:
                if done:
                    break
            
            with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as tmp:
                sf.write(tmp.name, chunk_info['data'], sample_rate)
                tmp_path = tmp.name
            
            try:
                result = pipe(tmp_path, return_timestamps=False)
                text = result.get("text", "").strip()
                
                if text:
                    all_chunks.append({
                        "text": text,
                        "start": round(chunk_info['start_time'], 3),
                        "end": round(chunk_info['end_time'], 3)
                    })
            finally:
                try:
                    os.unlink(tmp_path)
                except:
                    pass
        
        with lock:
            done = True
        
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
        
    except Exception as e:
        with lock:
            done = True
        import traceback
        print(json.dumps({"type": "error", "error": str(e), "traceback": traceback.format_exc()}), file=sys.stderr)
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(description="Transcribe audio with Parakeet")
    parser.add_argument("audio_file", nargs="?", help="Path to audio file")
    parser.add_argument("--model", type=str, default=DEFAULT_MODEL, help="HuggingFace ASR model id")
    parser.add_argument("--stream-chunks", action="store_true", help="Enable streaming mode (one JSON line per chunk)")
    parser.add_argument("--start-chunk", type=int, default=0, help="Chunk index to start from (for resume)")
    parser.add_argument("--chunk-length", type=int, default=15, help="Chunk length in seconds")
    parser.add_argument("--stride", type=int, default=5, help="Stride/overlap in seconds")
    parser.add_argument("--workers", type=int, default=DEFAULT_WORKERS, help="Number of parallel chunk workers")
    
    args = parser.parse_args()
    
    if not args.audio_file:
        print(json.dumps({"type": "error", "error": "No audio file provided"}), file=sys.stderr)
        sys.exit(1)
    
    if args.stream_chunks:
        def progress_cb(pct, idx, total):
            print(json.dumps({"type": "progress", "chunk": idx, "total_chunks": total, "pct": pct}), flush=True)
        
        try:
            for result in run_streaming_transcription(
                args.audio_file, 
                model=args.model,
                start_chunk=args.start_chunk,
                chunk_length_s=args.chunk_length,
                stride_length_s=args.stride,
                workers=args.workers,
                progress_callback=progress_cb
            ):
                print(json.dumps(result), flush=True)
        except Exception as e:
            import traceback
            print(json.dumps({"type": "error", "error": str(e), "traceback": traceback.format_exc()}), file=sys.stderr)
            sys.exit(1)
    else:
        run_standard_transcription(args.audio_file, model=args.model)


if __name__ == "__main__":
    main()