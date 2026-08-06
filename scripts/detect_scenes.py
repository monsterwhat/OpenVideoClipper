import sys
import json
import subprocess
import os

def parse_time(value):
    value = value.strip().strip('"')
    if not value:
        return None
    if ":" in value:
        try:
            seconds = 0.0
            for part in value.split(":"):
                seconds = seconds * 60 + float(part)
            return seconds
        except ValueError:
            return None
    try:
        return float(value)
    except ValueError:
        return None

def find_scenes_csv():
    for candidate in (os.path.join("scenes", "scenes.csv"), "scenes.csv"):
        if os.path.exists(candidate):
            return candidate
    return None

def run_scenedetect(video_path):
    try:
        # Use PySceneDetect via command line
        # --format csv for easy parsing
        # --output path
        cmd = [
            "scenedetect", "-i", video_path,
            "detect-content",
            "--list-scenes",
            "--output", "scenes"
        ]

        subprocess.run(cmd, capture_output=True, text=True, check=True)

        # PySceneDetect writes scenes.csv into the --output directory.
        csv_file = find_scenes_csv()
        if csv_file is None:
            print("Error: scenes.csv not found", file=sys.stderr)
            return []

        with open(csv_file, 'r') as f:
            lines = [line.strip() for line in f if line.strip()]

        start_col = None
        end_col = None
        data_lines = lines
        if lines:
            header = lines[0].lower()
            if "start" in header or "time" in header:
                cols = [c.strip().strip('"').lower() for c in header.split(",")]
                if "start time (seconds)" in cols:
                    start_col = cols.index("start time (seconds)")
                    end_col = cols.index("end time (seconds)")
                elif "start timecode" in cols:
                    start_col = cols.index("start timecode")
                    end_col = cols.index("end timecode")
                data_lines = lines[1:]

        scenes = []
        for line in data_lines:
            parts = [p.strip() for p in line.split(",")]
            if start_col is not None and end_col is not None and len(parts) > max(start_col, end_col):
                start = parse_time(parts[start_col])
                end = parse_time(parts[end_col])
            else:
                start = parse_time(parts[1]) if len(parts) > 1 else None
                end = parse_time(parts[2]) if len(parts) > 2 else None
            if start is not None and end is not None and end > start:
                scenes.append({"start": start, "end": end})

        return scenes
    except Exception as e:
        print("Error running scenedetect: %s" % e, file=sys.stderr)
        return []

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(json.dumps({"error": "No video path provided"}), file=sys.stderr)
        sys.exit(1)

    video_path = sys.argv[1]
    scenes = run_scenedetect(video_path)
    print(json.dumps({"scenes": scenes}))
