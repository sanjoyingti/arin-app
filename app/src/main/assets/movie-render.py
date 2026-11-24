#!/usr/bin/env python3
"""
Arin film studio renderer. Runs inside Arin's built-in Linux, in /root/movie.

Reads project.json (the production bible your model wrote) and renders it shot by shot:
  1. a photoreal still per shot (Pollinations, free, keyless), with the characters' fixed look
     descriptions written into every prompt for continuity;
  2. the spoken line per shot (Pollinations TTS, free, keyless), one fixed voice per character;
  3. optional real motion: image-to-video on a Hugging Face ZeroGPU Space (Wan 2.2), using the
     person's own free HF quota. When the quota runs out the shot falls back to a camera move
     over the still, and is marked so "Add motion" can upgrade it later;
  4. quality checks on every asset, scene-level resume, subtitles, and the final ffmpeg assembly.

Everything is resumable: finished shots are kept in state.json and never redone unless asked.
Nothing here ever calls a paid endpoint.
"""
import hashlib
import json
import os
import random
import re
import subprocess
import sys
import time
import urllib.parse

D = os.environ.get("ARIN_MOVIE_DIR", "/root/movie")
os.chdir(D)

HF_TOKEN = os.environ.get("ARIN_HF_TOKEN", "").strip()
SPACE = os.environ.get("ARIN_SPACE", "").strip()
SPACE_API = os.environ.get("ARIN_SPACE_API", "generate_video").strip()
SUBS = os.environ.get("ARIN_SUBS", "1") == "1"
MODE = os.environ.get("ARIN_MODE", "render")   # render = finish/resume; motion = upgrade still shots

W, H, FPS = 1280, 720, 25
MAX_IMAGE_TRIES = 3
MAX_MOTION_TRIES = 2
QUOTA_COOLDOWN = 6 * 3600   # free ZeroGPU quota resets 24h after first use; re-check after 6h

PHOTOREAL = ("photorealistic live-action film still, real human actors, natural skin texture and pores, "
             "realistic eyes, teeth and hands, shot on 35mm cinema camera, cinematic lighting, film grain")
NEGATIVE = ("cartoon, anime, illustration, painting, drawing, 3d render, cgi, video game, doll, toy, "
            "plastic skin, deformed, extra fingers, extra limbs, distorted face, blurry, text, watermark, logo, "
            "static, frozen, low quality")

LOG = open("render.log", "a")


def say(msg):
    with open("progress", "a") as f:
        f.write(msg + "\n")


def log(msg):
    LOG.write(time.strftime("%H:%M:%S ") + msg + "\n")
    LOG.flush()


def run(args, timeout=900):
    try:
        return subprocess.run(args, stdout=LOG, stderr=LOG, timeout=timeout).returncode == 0
    except Exception as e:  # timeout or missing tool
        log(f"run failed: {args[0]}: {e}")
        return False


def out(args, timeout=900):
    try:
        return subprocess.run(args, capture_output=True, text=True, timeout=timeout).stdout
    except Exception as e:
        log(f"out failed: {args[0]}: {e}")
        return ""


def load(path, default):
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return default


def save(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(data, f, indent=1)
    os.replace(tmp, path)


def backoff(attempt):
    time.sleep(min(60, 2 ** attempt) + random.uniform(0, 1.5))


# ---------- media checks (quality control) ----------

def probe(path):
    """(duration, has_video, has_audio, width) or None if the file isn't playable media."""
    if not os.path.exists(path) or os.path.getsize(path) < 1000:
        return None
    raw = out(["ffprobe", "-v", "error", "-show_entries", "format=duration:stream=codec_type,width",
               "-of", "json", path], 60)
    try:
        j = json.loads(raw)
    except Exception:
        return None
    streams = j.get("streams", [])
    vids = [s for s in streams if s.get("codec_type") == "video"]
    dur = float(j.get("format", {}).get("duration") or 0)
    return dur, bool(vids), any(s.get("codec_type") == "audio" for s in streams), (vids[0].get("width") if vids else 0) or 0


def mean_luma(path):
    """Average brightness 0-255 of the image (signalstats prints to stderr)."""
    try:
        r = subprocess.run(["ffmpeg", "-v", "info", "-i", path, "-vf",
                            "scale=160:-1,signalstats,metadata=print:key=lavfi.signalstats.YAVG",
                            "-frames:v", "1", "-f", "null", "-"], capture_output=True, text=True, timeout=60)
        m = re.search(r"YAVG=([\d.]+)", r.stderr)
        return float(m.group(1)) if m else None
    except Exception:
        return None


def image_ok(path):
    p = probe(path)
    if not p or not p[1] or p[3] < 256 or os.path.getsize(path) < 15000:
        return False, "not a usable image"
    y = mean_luma(path)
    if y is not None and y < 12:
        return False, "almost black"
    return True, ""


# ---------- production bible helpers ----------

PROJECT, CHARS, SHOTS, STYLE = None, {}, [], ""


def reload_project():
    """Re-read the plan. The person can steer the film while it renders; changes apply to unfilmed shots."""
    global PROJECT, CHARS, SHOTS, STYLE
    p = load("project.json", None)
    if p and p.get("shots"):
        PROJECT, CHARS, SHOTS, STYLE = p, {c["id"]: c for c in p.get("characters", [])}, p["shots"], p.get("style", "")


reload_project()
if not PROJECT:
    say("FAILED")
    sys.exit(1)


def shot_prompt(s):
    looks = [f'{CHARS[c]["name"]}: {CHARS[c]["look"]}' for c in s.get("characters", []) if c in CHARS]
    parts = [s.get("shot", ""), s.get("action", "")]
    if looks:
        parts.append("Characters (keep exactly): " + "; ".join(looks))
    if s.get("location"):
        parts.append("Location: " + s["location"])
    if STYLE:
        parts.append("Look: " + STYLE)
    parts.append(PHOTOREAL)
    return ". ".join(p.strip() for p in parts if p and p.strip())[:1800]


def motion_prompt(s):
    return ". ".join(x for x in [s.get("action", ""), "Camera: " + s.get("camera", "slow push in"),
                                 "realistic natural human motion, physically plausible", PHOTOREAL] if x)[:900]


def shot_seed(i, s):
    # A stable seed per lead character keeps faces from drifting as much between shots.
    lead = (s.get("characters") or ["scene"])[0]
    return (sum(ord(ch) for ch in lead) * 131 + i) % 2_000_000


def voice_for(s):
    sp = s.get("speaker", "narrator")
    if sp in CHARS and CHARS[sp].get("voice"):
        return CHARS[sp]["voice"]
    return PROJECT.get("narrator_voice", "onyx")


# ---------- free providers ----------

def get_image(i, s):
    path = f"s{i}.jpg"
    if os.path.exists(path) and image_ok(path)[0]:
        return True
    prompt = urllib.parse.quote(shot_prompt(s))
    for attempt in range(MAX_IMAGE_TRIES):
        seed = shot_seed(i, s) + attempt * 7919
        url = (f"https://image.pollinations.ai/prompt/{prompt}?width={W}&height={H}"
               f"&nologo=true&model=flux&seed={seed}")
        run(["curl", "-sL", "--max-time", "180", "-A", "Arin", "-e", "https://arin.app", url, "-o", path], 200)
        ok, why = image_ok(path)
        if ok:
            return True
        log(f"shot {i}: image rejected ({why}), attempt {attempt + 1}")
        backoff(attempt)
    return False


def get_voice(i, s):
    path = f"s{i}.mp3"
    line = (s.get("line") or "").strip()
    if os.path.exists(path) and (probe(path) or (0,))[0] > 0.3:
        return True
    if line:
        q = urllib.parse.quote(line[:900])
        url = f"https://text.pollinations.ai/{q}?model=openai-audio&voice={voice_for(s)}"
        for attempt in range(2):
            run(["curl", "-sL", "--max-time", "120", "-A", "Arin", "-e", "https://arin.app", url, "-o", path], 140)
            p = probe(path)
            if p and p[2] and p[0] > 0.3:
                return True
            log(f"shot {i}: voice failed, attempt {attempt + 1}")
            backoff(attempt)
    # No line, or TTS unavailable: room-tone silence so the shot still has its planned length.
    secs = str(max(2.5, float(s.get("seconds") or 4)))
    return run(["ffmpeg", "-y", "-f", "lavfi", "-i", "anullsrc=r=44100:cl=mono", "-t", secs,
                "-c:a", "libmp3lame", path], 60)


class Quota(Exception):
    pass


def space_base():
    # owner/name -> owner-name.hf.space
    return "https://" + re.sub(r"[^a-z0-9-]", "-", SPACE.lower().replace("/", "-")) + ".hf.space"


def gpu_available():
    if not HF_TOKEN or not SPACE:
        return False
    st = load("gpu.json", {})
    return time.time() >= st.get("quota_until", 0)


def mark_quota():
    save("gpu.json", {"quota_until": time.time() + QUOTA_COOLDOWN})


def get_motion(i, s):
    """Image-to-video on the Hugging Face Space. Returns True with s{i}.raw.mp4 on success."""
    base = space_base()
    auth = ["-H", f"Authorization: Bearer {HF_TOKEN}"]
    up = out(["curl", "-s", "--max-time", "120"] + auth + ["-F", f"files=@s{i}.jpg", f"{base}/gradio_api/upload"], 150)
    try:
        remote = json.loads(up)[0]
    except Exception:
        raise RuntimeError(f"upload failed: {up[:200]}")
    payload = {"data": [
        {"path": remote, "meta": {"_type": "gradio.FileData"}},
        motion_prompt(s), 4, NEGATIVE, 5, 1, 1, shot_seed(i, s), False,
    ]}
    with open("payload.json", "w") as f:
        json.dump(payload, f)
    started = out(["curl", "-s", "--max-time", "60", "-X", "POST"] + auth +
                  ["-H", "Content-Type: application/json", "-d", "@payload.json",
                   f"{base}/gradio_api/call/{SPACE_API}"], 90)
    try:
        event_id = json.loads(started)["event_id"]
    except Exception:
        raise RuntimeError(f"submit failed: {started[:200]}")
    say(f"Shot {i + 1}: waiting for free GPU…")
    stream = out(["curl", "-sN", "--max-time", "1500"] + auth +
                 [f"{base}/gradio_api/call/{SPACE_API}/{event_id}"], 1600)
    event, data = None, None
    for line in stream.splitlines():
        if line.startswith("event:"):
            event = line[6:].strip()
        elif line.startswith("data:") and event in ("complete", "error"):
            data = line[5:].strip()
            break
    if event != "complete":
        msg = (data or stream[-300:] or "no response").lower()
        if "quota" in msg or "exceeded" in msg or "gpu limit" in msg or "zerogpu" in msg:
            raise Quota(msg[:200])
        raise RuntimeError(f"space error: {msg[:200]}")
    result = json.loads(data)[0]
    video = result.get("video", result) if isinstance(result, dict) else None
    url = (video or {}).get("url") or f"{base}/gradio_api/file={(video or {}).get('path', '')}"
    run(["curl", "-sL", "--max-time", "300"] + auth + [url, "-o", f"s{i}.raw.mp4"], 320)
    p = probe(f"s{i}.raw.mp4")
    if not p or not p[1] or p[0] < 1:
        raise RuntimeError("downloaded clip is not valid video")
    return True


# ---------- per-shot clip assembly ----------

CAMERA_MOVES = {
    "push": "z='min(zoom+0.0012,1.18)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'",
    "pull": "z='if(eq(on,0),1.18,max(zoom-0.0012,1.0))':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'",
    "left": "z=1.12:x='max(0,(iw-iw/zoom)*(1-on/{f}))':y='ih/2-(ih/zoom/2)'",
    "right": "z=1.12:x='(iw-iw/zoom)*on/{f}':y='ih/2-(ih/zoom/2)'",
    "up": "z=1.12:x='iw/2-(iw/zoom/2)':y='max(0,(ih-ih/zoom)*(1-on/{f}))'",
}


def move_for(s):
    cam = (s.get("camera") or "").lower()
    if any(k in cam for k in ("pull", "dolly out", "zoom out", "reveal")):
        return "pull"
    if "left" in cam:
        return "left"
    if any(k in cam for k in ("right", "track", "pan")):
        return "right"
    if any(k in cam for k in ("tilt", "crane", "rise", "boom")):
        return "up"
    return "push"


def build_clip(i, s, motion):
    audio = probe(f"s{i}.mp3")
    dur = max(2.5, audio[0] + 0.4 if audio else float(s.get("seconds") or 4))
    clip = f"s{i}.mp4"
    common = ["-t", f"{dur:.2f}", "-r", str(FPS), "-c:v", "libx264", "-preset", "veryfast", "-crf", "21",
              "-pix_fmt", "yuv420p", "-c:a", "aac", "-ar", "44100", "-ac", "2", clip]
    if motion:
        raw = probe(f"s{i}.raw.mp4")[0]
        slow = min(max(dur / raw, 1.0), 1.6)   # never stretch more than 1.6x (avoids mushy motion)
        vf = (f"[0:v]scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},fps={FPS},"
              f"setpts={slow:.3f}*PTS,tpad=stop_mode=clone:stop_duration={dur:.2f},format=yuv420p[v]")
        ok = run(["ffmpeg", "-y", "-i", f"s{i}.raw.mp4", "-i", f"s{i}.mp3", "-filter_complex", vf,
                  "-map", "[v]", "-map", "1:a", "-af", "apad"] + common)
    else:
        frames = int(dur * FPS) + 1
        zp = CAMERA_MOVES[move_for(s)].replace("{f}", str(frames))
        vf = (f"[0:v]scale=1920:1080:force_original_aspect_ratio=increase,crop=1920:1080,"
              f"zoompan={zp}:d={frames}:s={W}x{H}:fps={FPS},format=yuv420p[v]")
        ok = run(["ffmpeg", "-y", "-loop", "1", "-i", f"s{i}.jpg", "-i", f"s{i}.mp3", "-filter_complex", vf,
                  "-map", "[v]", "-map", "1:a", "-af", "apad"] + common)
    p = probe(clip)
    return ok and p and p[1] and p[2] and p[0] > 1.5


# ---------- main ----------

def main():
    open("progress", "w").close()
    state = load("state.json", {})
    n = len(SHOTS)
    say(f"Production: {PROJECT.get('title', 'Untitled')} — {n} shots, {len(CHARS)} characters")
    if MODE == "motion" and not (HF_TOKEN and SPACE):
        say("Add a Hugging Face token first to get real motion.")

    i = -1
    while True:
        i += 1
        reload_project()
        n = len(SHOTS)
        if i >= n:
            break
        s = SHOTS[i]
        key = str(i)
        # If the plan for this shot changed (steering), throw away its old picture/voice/clip.
        fp = hashlib.sha1(json.dumps(s, sort_keys=True).encode()).hexdigest()[:16]
        fp_file = f"s{i}.fp"
        old_fp = open(fp_file).read().strip() if os.path.exists(fp_file) else None
        if old_fp != fp:
            if old_fp is not None:
                for ext in ("jpg", "mp3", "mp4", "raw.mp4"):
                    if os.path.exists(f"s{i}.{ext}"):
                        os.remove(f"s{i}.{ext}")
                state.pop(key, None)
            with open(fp_file, "w") as f:
                f.write(fp)
        rec = state.get(key, {})
        done = rec.get("status") in ("motion", "still") and (probe(f"s{i}.mp4") or (0, 0))[1]
        if done and not (MODE == "motion" and rec["status"] == "still" and gpu_available()):
            continue

        say(f"Shot {i + 1}/{n}: picture")
        if not get_image(i, s):
            state[key] = {"status": "failed", "reason": "image provider failed"}
            save("state.json", state)
            say(f"Shot {i + 1}/{n}: picture failed, will retry on Resume")
            continue
        say(f"Shot {i + 1}/{n}: voice")
        get_voice(i, s)

        motion = False
        if gpu_available():
            for attempt in range(MAX_MOTION_TRIES):
                try:
                    say(f"Shot {i + 1}/{n}: real motion (Hugging Face)")
                    motion = get_motion(i, s)
                    break
                except Quota as e:
                    log(f"shot {i}: free GPU quota used up: {e}")
                    mark_quota()
                    say("Free GPU quota used up for now — remaining shots use camera moves. "
                        "Tap Add motion later to upgrade them.")
                    break
                except Exception as e:
                    log(f"shot {i}: motion failed: {e}")
                    backoff(attempt + 1)

        say(f"Shot {i + 1}/{n}: editing")
        if not build_clip(i, s, motion) and motion:
            motion = False
            build_clip(i, s, False)
        if (probe(f"s{i}.mp4") or (0, 0))[1]:
            state[key] = {"status": "motion" if motion else "still", "t": int(time.time())}
        else:
            state[key] = {"status": "failed", "reason": "clip assembly failed"}
        save("state.json", state)

    reload_project()
    n = len(SHOTS)
    state = {k: v for k, v in state.items() if k.isdigit() and int(k) < n}
    # ---- assemble the film ----
    say("Final cut: assembling")
    clips = [i for i in range(n) if state.get(str(i), {}).get("status") in ("motion", "still")]
    if not clips:
        say("FAILED")
        return
    with open("list.txt", "w") as f:
        for i in clips:
            f.write(f"file 's{i}.mp4'\n")
    if not run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", "list.txt", "-c", "copy", "joined.mp4"]):
        run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", "list.txt", "-c:v", "libx264", "-preset",
             "veryfast", "-pix_fmt", "yuv420p", "-c:a", "aac", "joined.mp4"])

    # Subtitles from the actual clip timings.
    t = 0.0
    entries = []
    for i in clips:
        d = (probe(f"s{i}.mp4") or (0,))[0]
        line = (SHOTS[i].get("line") or "").strip()
        if line:
            entries.append((t, t + d - 0.2, line))
        t += d

    def ts(x):
        ms = int(round(x * 1000))
        return f"{ms // 3600000:02}:{ms // 60000 % 60:02}:{ms // 1000 % 60:02},{ms % 1000:03}"

    with open("movie.srt", "w") as f:
        for k, (a, b, line) in enumerate(entries, 1):
            f.write(f"{k}\n{ts(a)} --> {ts(b)}\n{line}\n\n")

    burned = False
    if SUBS and entries:
        say("Final cut: subtitles")
        burned = run(["ffmpeg", "-y", "-i", "joined.mp4", "-vf",
                      "subtitles=movie.srt:force_style='FontSize=18,Outline=1,MarginV=28'",
                      "-c:v", "libx264", "-preset", "veryfast", "-crf", "21", "-pix_fmt", "yuv420p",
                      "-c:a", "copy", "movie.mp4"], 3600)
    if not burned:
        os.replace("joined.mp4", "movie.mp4")

    p = probe("movie.mp4")
    if not p or not p[1] or p[0] < 1:
        say("FAILED")
        return
    counts = {k: sum(1 for i in range(n) if state.get(str(i), {}).get("status") == k)
              for k in ("motion", "still", "failed")}
    missing = n - len(clips)
    save("summary.json", {"shots": n, "seconds": round(p[0], 1), "missing": missing, **counts})
    say(f"{counts['motion']} shots with real motion, {counts['still']} with camera moves"
        + (f", {missing} missing" if missing else ""))
    say("DONE_PARTIAL" if missing else "DONE")


try:
    main()
except Exception as e:
    log(f"fatal: {e}")
    say("FAILED")
