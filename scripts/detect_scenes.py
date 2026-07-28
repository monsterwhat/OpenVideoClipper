import sys
import json
import subprocess
import math

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
        
        result = subprocess.run(cmd, capture_output=True, text=True, check=True)
        
        # PySceneDetect output is usually in a file named scenes.csv in the current directory
        # But let's check the command output for the list of scenes if possible
        # Actually, it's better to parse the file it creates.
        
        import os
        csv_file = "scenes.csv"
        if not os.path.exists(csv_file):
            # Try to find it if it's in a different location
            print(f"Error: {csv_file} not found", file=sys.stderr)
            return []

        scenes = []
        with open(csv_file, 'r') as f:
            lines = f.readlines()
            # Skip header
            for line in lines[1:]:
                parts = line.strip().split(',')
                if len(parts) >= 2:
                    start = float(parts[0])
                    end = float(parts[1])
                    scenes.append({"start": start, "end": end})
        
        return scenes
    except Exception as e:
        print(f"Error running scenedetect: {str(e)}", file=sys.stderr)
        return []

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(json.dumps({"error": "No video path provided"}), file=sys.stderr)
        sys.exit(1)

    video_path = sys.argv[1]
    scenes = run_scenedetect(video_path)
    print(json.dumps({"scenes": scenes}))
