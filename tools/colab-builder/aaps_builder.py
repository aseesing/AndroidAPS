"""AAPS Builder for Google Colab.

Builds AndroidAPS from the official source code, in the user's own Google account,
and signs it with the user's own key. The key and the finished app are kept in the
user's own Google Drive, in the folder AAPS.

The same code runs outside Colab for testing (see `python3 aaps_builder.py --help`).
Then a scripted UI answers the questions and a local folder stands in for Google Drive.
"""

import argparse
import base64
import datetime
import glob
import json
import os
import re
import secrets
import shutil
import subprocess
import tarfile
import time
import urllib.request
import zipfile

# Bump this when the text on the legal page changes. It is saved with every acceptance.
LEGAL_TEXT_VERSION = "2026-11-12"
LANGUAGES = ["en", "nl"]
AAPS_REPO = "https://github.com/nightscout/AndroidAPS.git"
# The oldest version that builds with JDK 21. Older versions need an older JDK.
MIN_VERSION = (3, 3)
JDK_URL = "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
SDK_TOOLS_URL = "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
SDK_LICENSE_URL = "https://developer.android.com/studio/terms"
VARIANTS = ["fullRelease", "aapsclientRelease", "aapsclient2Release", "pumpcontrolRelease"]
KEY_ALIAS = "key0"
# Used when the user chooses "no password". keytool does not accept an empty password,
# so the key gets this well known one. It protects nothing, and the UI says so.
NO_PASSWORD = "AndroidAPS"
KEYSTORE_FILE = "aaps-builder-keystore.jks"
CONFIG_FILE = "aaps-builder.json"


class BuildError(Exception):
    """An error with a message key from the language files."""

    def __init__(self, key, **values):
        super().__init__(key)
        self.key = key
        self.values = values


# ---------------------------------------------------------------- texts


class Texts:
    def __init__(self, asset_dir):
        self.all = {}
        for lang in LANGUAGES:
            with open(os.path.join(asset_dir, "i18n", lang + ".json"), encoding="utf-8") as f:
                self.all[lang] = json.load(f)
        self.lang = "en"

    def pick(self, browser_language):
        code = (browser_language or "en").split("-")[0].lower()
        self.lang = code if code in self.all else "en"

    def get(self, key, **values):
        text = self.all.get(self.lang, {}).get(key) or self.all["en"].get(key) or key
        if isinstance(text, list):
            return text
        return text.format(**values) if values else text


# ---------------------------------------------------------------- user interface


class ColabUi:
    """Shows the pages as HTML in the Colab output and waits for the button press."""

    def __init__(self, asset_dir, texts):
        from IPython.display import HTML, display
        from google.colab import output

        self.HTML = HTML
        self.display = display
        self.output = output
        self.texts = texts
        with open(os.path.join(asset_dir, "ui.html"), encoding="utf-8") as f:
            self.template = f.read()
        self.progress_handle = None
        self.last_update = 0.0

    def browser_language(self):
        try:
            return self.output.eval_js("navigator.language || 'en'")
        except Exception:
            return "en"

    def _page_html(self, page, data):
        token = secrets.token_hex(8)
        lang = self.texts.lang
        # Pages with questions get all languages, so the language can change on the page itself.
        strings = self.texts.all if page in ("legal", "options") else {lang: self.texts.all[lang],
                                                                       "en": self.texts.all["en"]}
        config = {"page": page, "lang": lang, "token": token, "strings": strings, "data": data}
        # Keep "</script>" inside a string from closing the script tag.
        config_json = json.dumps(config).replace("</", "<\\/")
        result = self.template.replace("__AAPS_TOKEN__", token).replace("__AAPS_CONFIG__", config_json)
        return result, token

    def ask(self, page, data):
        page_html, token = self._page_html(page, data)
        self.output.clear()
        self.progress_handle = None
        self.display(self.HTML(page_html))
        script = """
            new Promise(resolve => {
              const timer = setInterval(() => {
                const r = window['aapsResult_%s'];
                if (r) { clearInterval(timer); resolve(r); }
              }, 250);
            })""" % token
        result = json.loads(self.output.eval_js(script, timeout_sec=None))
        if result.get("lang") in self.texts.all:
            self.texts.lang = result["lang"]
        return result

    def show(self, page, data):
        page_html, _ = self._page_html(page, data)
        if page == "progress":
            if self.progress_handle is None:
                self.output.clear()
                self.progress_handle = self.display(self.HTML(page_html), display_id=True)
            else:
                self.progress_handle.update(self.HTML(page_html))
            return
        self.output.clear()
        self.progress_handle = None
        self.display(self.HTML(page_html))

    def mount_drive(self):
        from google.colab import drive

        drive.mount("/content/drive")
        return "/content/drive/MyDrive"

    def flush_drive(self):
        from google.colab import drive

        try:
            drive.flush_and_unmount()
        except Exception:
            pass

    def download(self, path):
        from google.colab import files

        try:
            files.download(path)
        except Exception:
            pass


class ScriptedUi:
    """For tests outside Colab: answers from command line options, prints to the console."""

    def __init__(self, texts, answers, drive_dir):
        self.texts = texts
        self.answers = answers
        self.drive_dir = drive_dir

    def browser_language(self):
        return self.answers.get("lang", "en")

    def ask(self, page, data):
        print("== page:", page, json.dumps(data, default=str)[:400])
        if page == "legal":
            return {"accepted": True, "lang": self.answers.get("lang", "en")}
        answer = dict(self.answers)
        answer.setdefault("mode", "update" if data.get("existing") else "new")
        answer.setdefault("version", data.get("latest"))
        answer.setdefault("variant", data.get("default_variant", "fullRelease"))
        answer.setdefault("wear", False)
        answer.setdefault("password_mode", "none")
        if data.get("error"):
            raise SystemExit("options page error: " + data["error"])
        return answer

    def show(self, page, data):
        if page == "progress":
            line = data.get("detail") or ""
            print("[%s] %s %s" % (time.strftime("%H:%M:%S"), data.get("step_text", ""), line[-160:]), flush=True)
        else:
            print("== page:", page, json.dumps(data, default=str, indent=1))

    def mount_drive(self):
        os.makedirs(self.drive_dir, exist_ok=True)
        return self.drive_dir

    def flush_drive(self):
        pass

    def download(self, path):
        print("download:", path)


# ---------------------------------------------------------------- helpers


def version_key(tag):
    return tuple(int(x) for x in tag.split("."))


def version_key_loose(text):
    """For SDK folder names like 36.0.0 or 37.0.0-rc1: compares the numbers only."""
    return tuple(int(x) for x in re.findall(r"\d+", text))


def list_versions():
    """Release tags of AAPS, newest first. Uses git, so no GitHub API rate limit."""
    out = subprocess.run(["git", "ls-remote", "--tags", "--refs", AAPS_REPO],
                         capture_output=True, text=True, timeout=120)
    if out.returncode != 0:
        raise BuildError("error_versions")
    tags = []
    for line in out.stdout.splitlines():
        tag = line.rsplit("refs/tags/", 1)[-1].strip()
        if re.fullmatch(r"\d+\.\d+\.\d+(\.\d+)?", tag) and version_key(tag)[:2] >= MIN_VERSION:
            tags.append(tag)
    tags.sort(key=version_key, reverse=True)
    if not tags:
        raise BuildError("error_versions")
    return tags


def total_ram_gb():
    override = os.environ.get("AAPS_BUILDER_RAM_GB")
    if override:
        return float(override)
    with open("/proc/meminfo") as f:
        for line in f:
            if line.startswith("MemTotal:"):
                return int(line.split()[1]) / 1024 / 1024
    return 12.0


def cpu_count():
    override = os.environ.get("AAPS_BUILDER_CPUS")
    return int(override) if override else (os.cpu_count() or 2)


def download(url, target):
    request = urllib.request.Request(url, headers={"User-Agent": "aaps-colab-builder"})
    with urllib.request.urlopen(request, timeout=300) as response, open(target, "wb") as f:
        shutil.copyfileobj(response, f)


def java_major(java_bin):
    try:
        out = subprocess.run([java_bin, "-version"], capture_output=True, text=True, timeout=60)
    except OSError:
        return 0
    match = re.search(r'version "(\d+)', out.stderr + out.stdout)
    return int(match.group(1)) if match else 0


def normalize_hex(text):
    return re.sub(r"[^0-9a-f]", "", text.lower())


def now_text():
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M")


# ---------------------------------------------------------------- the builder


class Builder:
    STEPS = ["step_java", "step_key", "step_sdk", "step_source", "step_build", "step_check", "step_save"]

    def __init__(self, ui, texts, work_dir):
        self.ui = ui
        self.t = texts
        self.work = work_dir
        os.makedirs(self.work, exist_ok=True)
        self.log_path = os.path.join(self.work, "build.log")
        open(self.log_path, "w").close()  # A new log for every run.
        self.step = 0
        self.task_count = 0
        self.started = time.time()
        self.java_home = None
        self.sdk = os.path.join(self.work, "android-sdk")

    # ------------------------------------------------ progress and logging

    def log(self, text):
        with open(self.log_path, "a", encoding="utf-8") as f:
            f.write(text if text.endswith("\n") else text + "\n")

    def progress(self, detail="", force=False):
        now = time.time()
        if not force and now - getattr(self, "_last_progress", 0) < 2:
            return
        self._last_progress = now
        steps = [{"text": self.t.get(key), "state": "done" if i < self.step else "now" if i == self.step else "todo"}
                 for i, key in enumerate(self.STEPS)]
        minutes = int((now - self.started) / 60)
        self.ui.show("progress", {"steps": steps, "detail": detail,
                                  "step_text": self.t.get(self.STEPS[min(self.step, len(self.STEPS) - 1)]),
                                  "elapsed": self.t.get("elapsed", minutes=minutes)})

    def next_step(self):
        self.step += 1
        self.progress(force=True)

    def run(self, args, cwd=None, env=None, error_key="error_command", input_text=None, count_tasks=False):
        """Runs a command, writes all output to the log and shows the last line as progress."""
        self.log("$ " + " ".join(args))
        process = subprocess.Popen(args, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   stdin=subprocess.PIPE if input_text is not None else subprocess.DEVNULL,
                                   text=True, bufsize=1)
        if input_text is not None:
            try:
                process.stdin.write(input_text)
                process.stdin.close()
            except BrokenPipeError:
                pass
        for line in process.stdout:
            self.log(line)
            if count_tasks and line.startswith("> Task "):
                self.task_count += 1
                self.progress(self.t.get("tasks_done", count=self.task_count))
            elif not count_tasks:
                self.progress(line.strip())
        process.wait()
        if process.returncode != 0:
            raise BuildError(error_key)

    def env(self):
        env = dict(os.environ)
        env["JAVA_HOME"] = self.java_home
        env["ANDROID_HOME"] = self.sdk
        env["ANDROID_SDK_ROOT"] = self.sdk
        env["PATH"] = os.path.join(self.java_home, "bin") + os.pathsep + env.get("PATH", "")
        return env

    # ------------------------------------------------ steps

    def setup_java(self):
        candidates = [os.environ.get("JAVA_HOME", "")] + sorted(glob.glob("/usr/lib/jvm/*21*"))
        candidates.append(os.path.join(self.work, "jdk"))
        for home in candidates:
            if home and java_major(os.path.join(home, "bin", "java")) == 21:
                self.java_home = home
                self.log("Using JDK " + home)
                return
        target = os.path.join(self.work, "jdk")
        try:
            archive = os.path.join(self.work, "jdk.tar.gz")
            self.progress(self.t.get("downloading", what="Java 21"), force=True)
            download(JDK_URL, archive)
            with tarfile.open(archive) as tar:
                top = tar.getnames()[0].split("/")[0]
                # The "tar" filter keeps the links inside the JDK but still blocks unsafe paths.
                if hasattr(tarfile, "tar_filter"):
                    tar.extractall(self.work, filter="tar")
                else:
                    tar.extractall(self.work)
            os.rename(os.path.join(self.work, top), target)
            os.remove(archive)
        except Exception as e:
            self.log("JDK download failed, trying apt: %r" % e)
            self.run(["apt-get", "install", "-y", "-q", "openjdk-21-jdk-headless"], error_key="error_java")
            target = sorted(glob.glob("/usr/lib/jvm/*21*"))[0]
        if java_major(os.path.join(target, "bin", "java")) != 21:
            raise BuildError("error_java")
        self.java_home = target

    def setup_sdk(self):
        sdkmanager = os.path.join(self.sdk, "cmdline-tools", "latest", "bin", "sdkmanager")
        if not os.path.exists(sdkmanager):
            self.progress(self.t.get("downloading", what="Android SDK"), force=True)
            archive = os.path.join(self.work, "cmdline-tools.zip")
            download(SDK_TOOLS_URL, archive)
            unpack = os.path.join(self.work, "cmdline-tools-unpack")
            shutil.rmtree(unpack, ignore_errors=True)
            with zipfile.ZipFile(archive) as z:
                z.extractall(unpack)
            os.makedirs(os.path.join(self.sdk, "cmdline-tools"), exist_ok=True)
            shutil.move(os.path.join(unpack, "cmdline-tools"), os.path.join(self.sdk, "cmdline-tools", "latest"))
            os.remove(archive)
            for name in os.listdir(os.path.join(self.sdk, "cmdline-tools", "latest", "bin")):
                path = os.path.join(self.sdk, "cmdline-tools", "latest", "bin", name)
                os.chmod(path, 0o755)
        # The user accepted the Android SDK license on the first page.
        self.run([sdkmanager, "--licenses"], env=self.env(), input_text="y\n" * 50, error_key="error_sdk")

    def get_source(self, version):
        src = os.path.join(self.work, "AndroidAPS-" + version)
        if os.path.isdir(os.path.join(src, ".git")):
            return src
        shutil.rmtree(src, ignore_errors=True)
        self.run(["git", "clone", "--depth", "1", "--branch", version, "--single-branch", AAPS_REPO, src],
                 error_key="error_source")
        return src

    def keytool(self):
        return os.path.join(self.java_home, "bin", "keytool")

    def secret_env(self, password, key_password=None):
        """Passwords go to the tools through the environment, so they never show in the log."""
        env = self.env()
        env["AAPS_KS_PASS"] = password
        env["AAPS_KEY_PASS"] = password if key_password is None else key_password
        return env

    def key_sha256(self, keystore, password, alias):
        out = subprocess.run([self.keytool(), "-list", "-v", "-keystore", keystore, "-storepass:env", "AAPS_KS_PASS",
                              "-alias", alias], capture_output=True, text=True, env=self.secret_env(password))
        match = re.search(r"SHA256:\s*([0-9A-Fa-f:]+)", out.stdout)
        if out.returncode != 0 or not match:
            return None
        return normalize_hex(match.group(1))

    def create_key(self, keystore, password):
        self.run([self.keytool(), "-genkeypair", "-keystore", keystore, "-storetype", "PKCS12",
                  "-alias", KEY_ALIAS, "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                  "-storepass:env", "AAPS_KS_PASS", "-keypass:env", "AAPS_KEY_PASS", "-dname", "CN=AAPS"],
                 env=self.secret_env(password), error_key="error_key")

    def build(self, src, variant, wear):
        """Builds the unsigned app. It is signed afterwards with `sign`, like the AAPS CLI guide does."""
        ram = total_ram_gb()
        heap = max(3, min(8, int(ram * 0.6)))
        tasks = [":app:assemble" + variant[0].upper() + variant[1:]]
        if wear:
            tasks.append(":wear:assemble" + variant[0].upper() + variant[1:])
        args = ["./gradlew", "--console=plain", "--no-daemon", "--max-workers=%d" % cpu_count(),
                "-Dorg.gradle.jvmargs=-Xmx%dg -XX:+UseParallelGC -Xss4m" % heap,
                # One heap for Gradle and Kotlin, so two big JVMs do not fight for the memory.
                "-Pkotlin.compiler.execution.strategy=in-process"] + tasks
        self.log("RAM %.1f GB, Gradle heap %d GB, %d workers" % (ram, heap, cpu_count()))
        os.chmod(os.path.join(src, "gradlew"), 0o755)
        self.run(args, cwd=src, env=self.env(), error_key="error_build", count_tasks=True)

    def build_tool(self, name):
        tools = sorted(glob.glob(os.path.join(self.sdk, "build-tools", "*", name)),
                       key=lambda p: version_key_loose(os.path.basename(os.path.dirname(p))))
        if not tools:
            raise BuildError("error_check")
        return tools[-1]

    def sign(self, unsigned, signed, keystore, password, alias, key_password):
        """Signs with apksigner. The passwords go in through the environment, never on the command line."""
        aligned = unsigned
        check = subprocess.run([self.build_tool("zipalign"), "-c", "-P", "16", "4", unsigned],
                               capture_output=True, text=True, env=self.env())
        if check.returncode != 0:
            aligned = unsigned + ".aligned"
            self.run([self.build_tool("zipalign"), "-f", "-P", "16", "4", unsigned, aligned],
                     env=self.env(), error_key="error_check")
        # v2 and v3, like an app signed by the Android Gradle plugin.
        self.run([self.build_tool("apksigner"), "sign", "--ks", keystore, "--ks-key-alias", alias,
                  "--ks-pass", "env:AAPS_KS_PASS", "--key-pass", "env:AAPS_KEY_PASS",
                  "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
                  "--out", signed, aligned], env=self.secret_env(password, key_password), error_key="error_sign")

    def check_signature(self, apk, expected_sha256):
        out = subprocess.run([self.build_tool("apksigner"), "verify", "--print-certs", apk], capture_output=True, text=True,
                             env=self.env())
        self.log(out.stdout + out.stderr)
        match = re.search(r"certificate SHA-256 digest:\s*([0-9a-fA-F]+)", out.stdout)
        if out.returncode != 0 or not match or normalize_hex(match.group(1)) != expected_sha256:
            raise BuildError("error_check")


# ---------------------------------------------------------------- the flow


def find_unsigned_apk(src, module, variant):
    flavor = variant[:-len("Release")]
    found = glob.glob(os.path.join(src, module, "build", "outputs", "apk", flavor, "release", "*-unsigned.apk"))
    return found[0] if found else None


def load_config(key_dir):
    path = os.path.join(key_dir, CONFIG_FILE)
    if os.path.exists(path) and os.path.exists(os.path.join(key_dir, KEYSTORE_FILE)):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    return None


def save_config(key_dir, config):
    path = os.path.join(key_dir, CONFIG_FILE)
    with open(path + ".tmp", "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2)
    os.replace(path + ".tmp", path)


def move_old_key_aside(key_dir):
    """Never delete a key. A new key only moves the old files to a dated name."""
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    for name in (KEYSTORE_FILE, CONFIG_FILE):
        path = os.path.join(key_dir, name)
        if os.path.exists(path):
            base, ext = os.path.splitext(name)
            os.rename(path, os.path.join(key_dir, "%s-old-%s%s" % (base, stamp, ext)))


def decode_keystore_set(text):
    """KEYSTORE_SET from the browser build: base64 of 'keystore-base64|store password|alias|key password'."""
    try:
        parts = base64.b64decode("".join(text.split())).decode("utf-8").split("|")
        return base64.b64decode(parts[0]), parts[1], parts[2], parts[3]
    except Exception:
        raise BuildError("error_keystore_set")


def options_data(config, versions, key_dir, error=None):
    last = (config or {}).get("last_build") or {}
    return {
        "existing": config is not None,
        "last": last,
        "password_saved": bool(config and config.get("password") is not None),
        "versions": versions,
        "latest": versions[0],
        "variants": VARIANTS,
        "default_variant": last.get("variant", "fullRelease"),
        "default_wear": bool(last.get("wear", False)),
        "browser_build_zip": bool(glob.glob(os.path.join(key_dir, "*.zip"))),
        "error": error,
    }


def prepare_key(builder, texts, choice, config, key_dir, local_key):
    """Checks the key choice and makes the key ready in local_key.

    Returns (key info, None) or (None, error text). Nothing is written to Drive here.
    """
    if os.path.exists(local_key):
        os.remove(local_key)
    if choice.get("mode") == "update":
        shutil.copy(os.path.join(key_dir, KEYSTORE_FILE), local_key)
        saved = config.get("password")
        password = saved if saved is not None else choice.get("password", "")
        alias = config.get("alias", KEY_ALIAS)
        key_password = config.get("key_password") or password
        sha = builder.key_sha256(local_key, password, alias)
        if not sha:
            return None, texts.get("error_password")
        return {"password": password, "alias": alias, "key_password": key_password, "sha": sha,
                "mode": config.get("password_mode", "own"), "new": False}, None

    if choice.get("keystore_set"):
        try:
            data, password, alias, key_password = decode_keystore_set(choice["keystore_set"])
        except BuildError as e:
            return None, texts.get(e.key)
        with open(local_key, "wb") as f:
            f.write(data)
        mode = "imported"
    elif choice.get("password_mode") == "own":
        password = choice.get("password", "")
        if len(password) < 6:
            return None, texts.get("error_password_short")
        alias, key_password, mode = KEY_ALIAS, password, "own"
        builder.create_key(local_key, password)
    else:
        password, alias, key_password, mode = NO_PASSWORD, KEY_ALIAS, NO_PASSWORD, "none"
        builder.create_key(local_key, password)
    sha = builder.key_sha256(local_key, password, alias)
    if not sha:
        return None, texts.get("error_keystore_set" if mode == "imported" else "error_key")
    return {"password": password, "alias": alias, "key_password": key_password, "sha": sha, "mode": mode,
            "new": True, "remember": mode != "own" or bool(choice.get("remember", True))}, None


def main_flow(ui, texts, work_dir):
    texts.pick(ui.browser_language())
    accepted = ui.ask("legal", {"text_version": LEGAL_TEXT_VERSION, "sdk_license_url": SDK_LICENSE_URL})
    if not accepted.get("accepted"):
        return
    acceptance = {"date": now_text(), "text_version": LEGAL_TEXT_VERSION, "lang": texts.lang}

    drive_root = ui.mount_drive()
    aaps_dir = os.path.join(drive_root, "AAPS")
    key_dir = os.path.join(aaps_dir, "KeyStore")
    log_dir = os.path.join(aaps_dir, "Builder", "logs")
    for d in (key_dir, log_dir):
        os.makedirs(d, exist_ok=True)

    builder = Builder(ui, texts, work_dir)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    try:
        # Java first: keytool is needed to check the key on the next page.
        builder.progress(force=True)
        builder.setup_java()
        config = load_config(key_dir)
        versions = list_versions()
        local_key = os.path.join(work_dir, KEYSTORE_FILE)

        error = None
        while True:
            choice = ui.ask("options", options_data(config, versions, key_dir, error))
            mode = choice.get("mode")
            if (choice.get("version") not in versions or choice.get("variant") not in VARIANTS
                    or mode not in ("update", "new") or (mode == "update" and config is None)):
                error = texts.get("error_choice")
                continue
            key, error = prepare_key(builder, texts, choice, config, key_dir, local_key)
            if key:
                break
        version, variant, wear = choice["version"], choice["variant"], bool(choice.get("wear"))

        if key["new"]:
            # Save a new key at once, before the long build, so it can never get lost.
            move_old_key_aside(key_dir)
            shutil.copy(local_key, os.path.join(key_dir, KEYSTORE_FILE))
            remember = key["remember"]
            config = {
                "schema": 1,
                "alias": key["alias"],
                "password": key["password"] if remember else None,
                "key_password": key["key_password"] if remember and key["key_password"] != key["password"] else None,
                "password_mode": key["mode"],
                "keystore": KEYSTORE_FILE,
                "cert_sha256": key["sha"],
                "created": now_text(),
                "acceptances": [],
            }
            save_config(key_dir, config)
        builder.step = 2
        builder.progress(force=True)
        builder.setup_sdk()
        builder.next_step()
        src = builder.get_source(version)
        builder.next_step()
        builder.build(src, variant, wear)
        builder.next_step()

        apks = [("aaps", find_unsigned_apk(src, "app", variant))]
        if wear:
            apks.append(("aaps-wear", find_unsigned_apk(src, "wear", variant)))
        out_dir = os.path.join(aaps_dir, version)
        os.makedirs(out_dir, exist_ok=True)
        saved = []
        suffix = "" if variant == "fullRelease" else "-" + variant[:-len("Release")]
        for prefix, apk in apks:
            if not apk:
                raise BuildError("error_check")
            name = "%s-%s%s.apk" % (prefix, version, suffix)
            local_copy = os.path.join(work_dir, name)
            builder.sign(apk, local_copy, local_key, key["password"], key["alias"], key["key_password"])
            builder.check_signature(local_copy, key["sha"])
            shutil.copy(local_copy, os.path.join(out_dir, name))
            saved.append((name, local_copy))
        builder.next_step()

        config["last_build"] = {"version": version, "variant": variant, "wear": wear, "date": now_text()}
        config.setdefault("acceptances", []).append(acceptance)
        save_config(key_dir, config)
        shutil.copy(builder.log_path, os.path.join(log_dir, "build-%s.log" % stamp))
        minutes = int((time.time() - builder.started) / 60)
        ui.show("done", {"files": ["AAPS/%s/%s" % (version, n) for n, _ in saved], "minutes": minutes,
                         "version": version, "new_key": key["new"],
                         "no_password": key["mode"] == "none"})
        ui.download(saved[0][1])
        ui.flush_drive()
    except BuildError as e:
        _fail(ui, texts, builder, log_dir, stamp, texts.get(e.key, **e.values))
    except Exception as e:  # Show any other problem in the same friendly way, with the log.
        builder.log("Unexpected error: %r" % e)
        _fail(ui, texts, builder, log_dir, stamp, texts.get("error_unexpected", error=repr(e)))


def _fail(ui, texts, builder, log_dir, stamp, message):
    log_name = "build-%s.log" % stamp
    tail = ""
    try:
        with open(builder.log_path, encoding="utf-8", errors="replace") as f:
            tail = "".join(f.readlines()[-40:])
        shutil.copy(builder.log_path, os.path.join(log_dir, log_name))
    except Exception:
        pass
    ui.show("error", {"message": message, "log": tail, "log_file": "AAPS/Builder/logs/" + log_name})
    ui.flush_drive()
    if isinstance(ui, ScriptedUi):
        raise SystemExit(1)


def asset_dir_for(base_url):
    """Next to this file when it is there, else downloaded from base_url."""
    here = os.path.dirname(os.path.abspath(__file__))
    if os.path.exists(os.path.join(here, "ui.html")):
        return here
    target = "/content/aaps-builder-assets"
    os.makedirs(os.path.join(target, "i18n"), exist_ok=True)
    for name in ["ui.html"] + ["i18n/%s.json" % lang for lang in LANGUAGES]:
        download(base_url.rstrip("/") + "/" + name, os.path.join(target, name))
    return target


def run_in_colab(base_url):
    assets = asset_dir_for(base_url)
    texts = Texts(assets)
    ui = ColabUi(assets, texts)
    main_flow(ui, texts, "/content/aaps-builder-work")


def main():
    parser = argparse.ArgumentParser(description="Test the AAPS Colab builder outside Colab.")
    parser.add_argument("--work", required=True, help="work folder (JDK, SDK, source)")
    parser.add_argument("--drive", required=True, help="folder that stands in for Google Drive")
    parser.add_argument("--version", help="AAPS version, default: newest")
    parser.add_argument("--variant", default=None, choices=VARIANTS)
    parser.add_argument("--wear", action="store_true")
    parser.add_argument("--mode", choices=["update", "new"])
    parser.add_argument("--password-mode", choices=["none", "own"], default="none")
    parser.add_argument("--password", default="")
    parser.add_argument("--no-remember", action="store_true")
    parser.add_argument("--keystore-set", default=None)
    parser.add_argument("--lang", default="en")
    a = parser.parse_args()
    answers = {"lang": a.lang, "wear": a.wear, "password_mode": a.password_mode, "password": a.password,
               "remember": not a.no_remember}
    for key, value in (("version", a.version), ("variant", a.variant), ("mode", a.mode),
                       ("keystore_set", a.keystore_set)):
        if value:
            answers[key] = value
    texts = Texts(os.path.dirname(os.path.abspath(__file__)))
    main_flow(ScriptedUi(texts, answers, a.drive), texts, a.work)


if __name__ == "__main__":
    main()
