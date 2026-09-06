package com.example.runner.ui

data class DocExample(
    val id: String,
    val title: String,
    val category: String,
    val summary: String,
    val explanation: String,
    val codeSnippet: String
)

object HelpDocumentation {

    val CATEGORIES = listOf(
        "All Examples",
        "Overview & Guides",
        "HTML / JS Apps",
        "Python Scripts",
        "Backend Bridge"
    )

    val OVERVIEW_GUIDES = listOf(
        DocExample(
            id = "guide_detect_run",
            title = "How Runner Works: DETECT → RUN → DISPLAY",
            category = "Overview & Guides",
            summary = "Runner detects project type automatically and executes it in an embedded WebView or isolated Python backend.",
            explanation = """
Runner evaluates project folders in this order:
1. runner.json — If present, uses explicit declarative config ("type": "html" or "python", "entry": "index.html").
2. HTML + Python Bridge — If both index.html and app2.py (or pybackend.py) exist, launches WebView and starts isolated :python_backend process.
3. HTML Web Project — If index.html exists, launches embedded WebView with local asset interception.
4. Python Script — If main.py, app.py, or run.py exists, executes script in embedded Python runtime and streams logs.
5. Terminal — Interactive execution and pip package management.
            """.trimIndent(),
            codeSnippet = """
// runner.json example
{
  "name": "My Custom App",
  "type": "html",
  "entry": "index.html"
}
            """.trimIndent()
        ),
        DocExample(
            id = "guide_bridge_api",
            title = "JavaScript Bridge Contract",
            category = "Overview & Guides",
            summary = "Communicate between frontend WebView and backend Python runtime.",
            explanation = """
When a project contains both index.html and a Python backend (like app2.py):
- Runner injects window.RunnerBackend into the WebView.
- Functions defined in Python are exported into globals.
- JavaScript invokes backend functions:
    window.RunnerBackend.call(functionName, ...args)
- Runner serializes arguments, forwards via Android Binder IPC to :python_backend, calls the Python function, and returns the result synchronously.
            """.trimIndent(),
            codeSnippet = """
// JavaScript (frontend):
const result = window.RunnerBackend.call("my_backend_function", param1, param2);
console.log("Returned from Python:", result);

# Python (app2.py backend):
def my_backend_function(param1, param2):
    return f"Processed: {param1} and {param2}"
            """.trimIndent()
        )
    )

    val EXAMPLES = listOf(
        // 1. Hello World HTML
        DocExample(
            id = "ex_01",
            title = "1. Hello World HTML Page",
            category = "HTML / JS Apps",
            summary = "Clean single-page display layout with responsive typography.",
            explanation = "A minimal HTML5 project. Place in the project folder as index.html. Runner detects index.html and displays it immediately.",
            codeSnippet = """
<!-- index.html -->
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Hello World</title>
  <style>
    body {
      font-family: system-ui, sans-serif;
      background: #0f172a;
      color: #f8fafc;
      display: flex;
      justify-content: center;
      align-items: center;
      height: 100vh;
      margin: 0;
    }
    h1 { color: #38bdf8; font-size: 2rem; }
  </style>
</head>
<body>
  <h1>Hello from Runner!</h1>
</body>
</html>
            """.trimIndent()
        ),

        // 2. Interactive Counter
        DocExample(
            id = "ex_02",
            title = "2. Interactive Counter",
            category = "HTML / JS Apps",
            summary = "Basic DOM manipulation with increment, decrement, and reset.",
            explanation = "Demonstrates JavaScript event listeners and state updates running locally inside Runner's WebView.",
            codeSnippet = """
<!-- index.html -->
<div style="text-align:center; padding: 40px; font-family: sans-serif;">
  <h2 id="count" style="font-size: 48px; color: #0284c7;">0</h2>
  <button onclick="update(-1)">-1</button>
  <button onclick="update(0)">Reset</button>
  <button onclick="update(1)">+1</button>
</div>
<script>
  let count = 0;
  function update(delta) {
    count = delta === 0 ? 0 : count + delta;
    document.getElementById("count").textContent = count;
  }
</script>
            """.trimIndent()
        ),

        // 3. Modern Calculator
        DocExample(
            id = "ex_03",
            title = "3. Modern Calculator Example",
            category = "HTML / JS Apps",
            summary = "Grid-based touch calculator supporting standard arithmetic.",
            explanation = "Educational implementation of an on-screen calculator keypad with display parsing.",
            codeSnippet = """
<!-- index.html -->
<div class="calc">
  <div id="display">0</div>
  <div class="grid">
    <button onclick="clearDisplay()">C</button>
    <button onclick="append('/')">÷</button>
    <button onclick="append('*')">×</button>
    <button onclick="append('-')">-</button>
    <button onclick="append('7')">7</button>
    <button onclick="append('8')">8</button>
    <button onclick="append('9')">9</button>
    <button onclick="append('+')">+</button>
    <button onclick="append('4')">4</button>
    <button onclick="append('5')">5</button>
    <button onclick="append('6')">6</button>
    <button onclick="calculate()">=</button>
    <button onclick="append('1')">1</button>
    <button onclick="append('2')">2</button>
    <button onclick="append('3')">3</button>
    <button onclick="append('0')">0</button>
  </div>
</div>
<script>
  let expr = "";
  function append(val) { expr += val; document.getElementById("display").innerText = expr; }
  function clearDisplay() { expr = ""; document.getElementById("display").innerText = "0"; }
  function calculate() {
    try { expr = String(eval(expr)); document.getElementById("display").innerText = expr; }
    catch(e) { document.getElementById("display").innerText = "Error"; expr = ""; }
  }
</script>
            """.trimIndent()
        ),

        // 4. Todo List
        DocExample(
            id = "ex_04",
            title = "4. Responsive Todo List Example",
            category = "HTML / JS Apps",
            summary = "Task management with localStorage persistence and item completion.",
            explanation = "Illustrates DOM creation, task filtering, and saving data in WebView localStorage.",
            codeSnippet = """
<!-- index.html -->
<div style="max-width: 400px; margin: 20px auto; font-family: sans-serif;">
  <h3>My Tasks</h3>
  <input id="taskInput" placeholder="New task..." style="padding: 8px; width: 70%;">
  <button onclick="addTask()" style="padding: 8px;">Add</button>
  <ul id="taskList" style="padding-left: 20px; margin-top: 16px;"></ul>
</div>
<script>
  const tasks = JSON.parse(localStorage.getItem("tasks") || "[]");
  function render() {
    document.getElementById("taskList").innerHTML = tasks.map((t, i) =>
      `<li>${'$'}{t} <a href="#" onclick="removeTask(${'$'}{i})">✖</a></li>`
    ).join("");
    localStorage.setItem("tasks", JSON.stringify(tasks));
  }
  function addTask() {
    const val = document.getElementById("taskInput").value.trim();
    if (val) { tasks.push(val); render(); document.getElementById("taskInput").value = ""; }
  }
  function removeTask(i) { tasks.splice(i, 1); render(); }
  render();
</script>
            """.trimIndent()
        ),

        // 5. Form Validation
        DocExample(
            id = "ex_05",
            title = "5. Form Validation Example",
            category = "HTML / JS Apps",
            summary = "Input validation with instant user feedback.",
            explanation = "Demonstrates client-side field validation for emails, passwords, and form submissions.",
            codeSnippet = """
<!-- index.html -->
<form onsubmit="return validate(event)" style="padding: 20px; font-family: sans-serif;">
  <div>
    <label>Email:</label><br>
    <input type="email" id="email" required style="width: 100%; padding: 8px;">
  </div>
  <div style="margin-top: 12px;">
    <label>Message:</label><br>
    <textarea id="msg" required style="width: 100%; padding: 8px;"></textarea>
  </div>
  <button type="submit" style="margin-top: 12px; padding: 10px 20px;">Submit</button>
  <p id="feedback" style="color: green;"></p>
</form>
<script>
  function validate(e) {
    e.preventDefault();
    document.getElementById("feedback").innerText = "Form submitted successfully!";
    return false;
  }
</script>
            """.trimIndent()
        ),

        // 6. Digital Clock & Stopwatch
        DocExample(
            id = "ex_06",
            title = "6. Digital Clock & Stopwatch",
            category = "HTML / JS Apps",
            summary = "Live time display and millisecond stopwatch using setInterval.",
            explanation = "Shows proper timer lifecycle and formatting in JavaScript.",
            codeSnippet = """
<!-- index.html -->
<div style="text-align: center; padding: 30px; font-family: monospace;">
  <h1 id="clock" style="font-size: 36px;">00:00:00</h1>
  <div style="margin-top: 20px;">
    <span id="stopwatch" style="font-size: 24px;">0.0s</span><br><br>
    <button onclick="toggleStopwatch()">Start / Stop</button>
    <button onclick="resetStopwatch()">Reset</button>
  </div>
</div>
<script>
  setInterval(() => {
    document.getElementById("clock").innerText = new Date().toLocaleTimeString();
  }, 1000);

  let swInterval = null, swSec = 0;
  function toggleStopwatch() {
    if (swInterval) { clearInterval(swInterval); swInterval = null; }
    else { swInterval = setInterval(() => { swSec += 0.1; document.getElementById("stopwatch").innerText = swSec.toFixed(1) + "s"; }, 100); }
  }
  function resetStopwatch() { swSec = 0; document.getElementById("stopwatch").innerText = "0.0s"; }
</script>
            """.trimIndent()
        ),

        // 7. Weather Card Interface
        DocExample(
            id = "ex_07",
            title = "7. Weather Card Interface",
            category = "HTML / JS Apps",
            summary = "Responsive UI card displaying meteorological statistics.",
            explanation = "Modern CSS card design with flexbox, gradients, and subtle shadows.",
            codeSnippet = """
<!-- index.html -->
<div style="background: linear-gradient(135deg, #1e3a8a, #3b82f6); color: white; padding: 24px; border-radius: 16px; max-width: 320px; margin: 40px auto; font-family: sans-serif;">
  <div style="font-size: 14px; opacity: 0.8;">San Francisco, CA</div>
  <div style="font-size: 48px; font-weight: bold; margin: 8px 0;">72°F</div>
  <div style="font-size: 16px;">Sunny & Clear</div>
  <hr style="opacity: 0.3; margin: 16px 0;">
  <div style="display: flex; justify-content: space-between; font-size: 13px;">
    <span>Humidity: 45%</span>
    <span>Wind: 8 mph</span>
  </div>
</div>
            """.trimIndent()
        ),

        // 8. Markdown Live Previewer
        DocExample(
            id = "ex_08",
            title = "8. Markdown Live Previewer",
            category = "HTML / JS Apps",
            summary = "Real-time markdown text parser to formatted HTML.",
            explanation = "Demonstrates live input binding converting headings, bold, italics, and lists into HTML.",
            codeSnippet = """
<!-- index.html -->
<div style="display: flex; height: 100vh; font-family: sans-serif;">
  <textarea id="src" oninput="parse()" style="width: 50%; padding: 12px; font-family: monospace;"># Header
**Bold** and *Italic*
- List item 1
- List item 2</textarea>
  <div id="dest" style="width: 50%; padding: 12px; background: #f8fafc; border-left: 1px solid #ccc;"></div>
</div>
<script>
  function parse() {
    let t = document.getElementById("src").value
      .replace(/^# (.*${'$'})/gim, '<h1>${'$'}1</h1>')
      .replace(/\*\*(.*)\*\*/gim, '<b>${'$'}1</b>')
      .replace(/\*(.*)\*/gim, '<i>${'$'}1</i>')
      .replace(/^\- (.*${'$'})/gim, '<li>${'$'}1</li>');
    document.getElementById("dest").innerHTML = t;
  }
  parse();
</script>
            """.trimIndent()
        ),

        // 9. Canvas Drawing Pad
        DocExample(
            id = "ex_09",
            title = "9. HTML5 Canvas Drawing Pad",
            category = "HTML / JS Apps",
            summary = "Touch and stylus finger painting canvas.",
            explanation = "Uses HTML5 Canvas 2D context with touchstart, touchmove, and touchend handlers.",
            codeSnippet = """
<!-- index.html -->
<canvas id="c" width="360" height="480" style="border: 1px solid #94a3b8; display: block; margin: 10px auto;"></canvas>
<div style="text-align: center;"><button onclick="ctx.clearRect(0,0,360,480)">Clear</button></div>
<script>
  const c = document.getElementById("c");
  const ctx = c.getContext("2d");
  let drawing = false;
  c.ontouchstart = (e) => { drawing = true; ctx.beginPath(); };
  c.ontouchend = () => drawing = false;
  c.ontouchmove = (e) => {
    if (!drawing) return;
    const r = c.getBoundingClientRect();
    const t = e.touches[0];
    ctx.lineTo(t.clientX - r.left, t.clientY - r.top);
    ctx.stroke();
  };
</script>
            """.trimIndent()
        ),

        // 10. Web Audio Tone Generator
        DocExample(
            id = "ex_10",
            title = "10. Web Audio Tone Generator",
            category = "HTML / JS Apps",
            summary = "Synthesizes sine wave audio frequencies using Web Audio API.",
            explanation = "Demonstrates interactive browser audio synthesis without external sound files.",
            codeSnippet = """
<!-- index.html -->
<div style="text-align: center; padding: 40px; font-family: sans-serif;">
  <h3>Tone Generator</h3>
  <button onclick="playTone(440)">A (440 Hz)</button>
  <button onclick="playTone(523.25)">C (523 Hz)</button>
  <button onclick="playTone(659.25)">E (659 Hz)</button>
</div>
<script>
  function playTone(freq) {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const osc = ctx.createOscillator();
    osc.frequency.value = freq;
    osc.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.3);
  }
</script>
            """.trimIndent()
        ),

        // 11. Python Hello World & System Info
        DocExample(
            id = "ex_11",
            title = "11. Python Hello World & System Info",
            category = "Python Scripts",
            summary = "Displays Python version, platform, and standard system paths.",
            explanation = "Runner runs Python scripts directly when main.py or app.py is selected.",
            codeSnippet = """
# main.py
import sys
import os

print("=== Runner Python Environment ===")
print("Python version:", sys.version)
print("Current directory:", os.getcwd())
print("File listing:", os.listdir("."))
print("Environment PATH:", os.environ.get("PATH", "default"))
print("Done!")
            """.trimIndent()
        ),

        // 12. Python Math & Fibonacci
        DocExample(
            id = "ex_12",
            title = "12. Python Fibonacci & Prime Generator",
            category = "Python Scripts",
            summary = "Algorithm execution with formatted console output.",
            explanation = "Demonstrates loops, list comprehensions, and functions in Runner's Python engine.",
            codeSnippet = """
# main.py
def fibonacci(n):
    seq = [0, 1]
    while len(seq) < n:
        seq.append(seq[-1] + seq[-2])
    return seq

def is_prime(num):
    if num < 2: return False
    for i in range(2, int(num**0.5) + 1):
        if num % i == 0: return False
    return True

print("First 15 Fibonacci numbers:", fibonacci(15))
primes = [x for x in range(2, 50) if is_prime(x)]
print("Primes under 50:", primes)
            """.trimIndent()
        ),

        // 13. Python File I/O
        DocExample(
            id = "ex_13",
            title = "13. Python File I/O",
            category = "Python Scripts",
            summary = "Reading, writing, and updating text files locally.",
            explanation = "Runner's runtime supports open(), read(), and write() mapped to the project folder.",
            codeSnippet = """
# app.py
filename = "data_log.txt"

# Write data
with open(filename, "w") as f:
    f.write("Line 1: Runner initialization\n")
    f.write("Line 2: Processing completed successfully\n")

print("File written. Reading back:")
with open(filename, "r") as f:
    content = f.read()
    print(content)
            """.trimIndent()
        ),

        // 14. Python Data Analyzer
        DocExample(
            id = "ex_14",
            title = "14. Python Data Analyzer & Statistics",
            category = "Python Scripts",
            summary = "Statistical calculations (mean, median, variance) over numeric data.",
            explanation = "Computes summary metrics without requiring heavy third-party packages.",
            codeSnippet = """
# app.py
scores = [88, 92, 79, 93, 85, 91, 100, 77, 84, 95]

mean = sum(scores) / len(scores)
sorted_scores = sorted(scores)
mid = len(sorted_scores) // 2
median = (sorted_scores[mid] if len(sorted_scores) % 2 != 0
          else (sorted_scores[mid - 1] + sorted_scores[mid]) / 2)

variance = sum((x - mean) ** 2 for x in scores) / len(scores)

print(f"Count: {len(scores)}")
print(f"Mean: {mean:.2f}")
print(f"Median: {median}")
print(f"StdDev: {variance ** 0.5:.2f}")
            """.trimIndent()
        ),

        // 15. Python HTTP Requests
        DocExample(
            id = "ex_15",
            title = "15. Python HTTP Requests",
            category = "Python Scripts",
            summary = "Fetch web content using requests or urllib.",
            explanation = "Demonstrates outbound HTTP GET request handling from Python in Runner.",
            codeSnippet = """
# main.py
import urllib.request
import json

url = "https://api.github.com/zen"
req = urllib.request.Request(url, headers={"User-Agent": "RunnerApp"})
try:
    with urllib.request.urlopen(req) as resp:
        print("GitHub Zen:", resp.read().decode("utf-8"))
except Exception as e:
    print("Request failed:", e)
            """.trimIndent()
        ),

        // 16. Bridge: Background Math Calculator
        DocExample(
            id = "ex_16",
            title = "16. Bridge: Background Math Calculator",
            category = "Backend Bridge",
            summary = "Delegates heavy numeric calculations from WebView to Python backend.",
            explanation = "JavaScript calls window.RunnerBackend.call('compute_factorial', 20) which executes in Python and returns the result.",
            codeSnippet = """
<!-- index.html -->
<input id="num" type="number" value="10">
<button onclick="calc()">Calculate Factorial</button>
<p id="out"></p>
<script>
  function calc() {
    const n = parseInt(document.getElementById("num").value);
    const res = window.RunnerBackend.call("compute_factorial", n);
    document.getElementById("out").innerText = "Result: " + res;
  }
</script>

# app2.py
import math
def compute_factorial(n):
    return math.factorial(int(n))
            """.trimIndent()
        ),

        // 17. Bridge: System Status & Memory Query
        DocExample(
            id = "ex_17",
            title = "17. Bridge: System Status Query",
            category = "Backend Bridge",
            summary = "Check backend process status and uptime from the UI.",
            explanation = "Shows how to invoke built-in status methods and custom diagnostics across the bridge.",
            codeSnippet = """
<!-- index.html -->
<button onclick="check()">Get Backend Diagnostics</button>
<pre id="diag"></pre>
<script>
  function check() {
    const st = window.RunnerBackend.status();
    const custom = window.RunnerBackend.call("get_diagnostics");
    document.getElementById("diag").innerText = JSON.stringify({ bridge: st, custom: custom }, null, 2);
  }
</script>

# app2.py
import sys, os
def get_diagnostics():
    return {
        "cwd": os.getcwd(),
        "python": sys.version.split()[0],
        "status": "HEALTHY"
    }
            """.trimIndent()
        ),

        // 18. Bridge: Local Data Storage / Log Saver
        DocExample(
            id = "ex_18",
            title = "18. Bridge: Local File Logger",
            category = "Backend Bridge",
            summary = "Save user notes or application logs to a file via Python.",
            explanation = "The frontend sends note text across the bridge, and Python writes it to a persistent local text file.",
            codeSnippet = """
<!-- index.html -->
<textarea id="noteText"></textarea>
<button onclick="saveNote()">Save to Local File</button>
<script>
  function saveNote() {
    const text = document.getElementById("noteText").value;
    const ok = window.RunnerBackend.call("save_note", text);
    alert(ok ? "Saved successfully!" : "Failed to save");
  }
</script>

# app2.py
def save_note(text):
    try:
        with open("user_notes.txt", "a") as f:
            f.write(text + "\n---\n")
        return True
    except Exception:
        return False
            """.trimIndent()
        ),

        // 19. Bridge: Text Parser & Keyword Extractor
        DocExample(
            id = "ex_19",
            title = "19. Bridge: Text Keyword Extractor",
            category = "Backend Bridge",
            summary = "Analyze text in Python and return top keywords to frontend.",
            explanation = "Demonstrates returning structured objects/dictionaries from Python back to JavaScript.",
            codeSnippet = """
<!-- index.html -->
<textarea id="inputArticle" placeholder="Paste article..."></textarea>
<button onclick="extract()">Extract Keywords</button>
<div id="tags"></div>
<script>
  function extract() {
    const txt = document.getElementById("inputArticle").value;
    const words = window.RunnerBackend.call("extract_keywords", txt);
    document.getElementById("tags").innerHTML = words.map(w =>
      `<span style="background:#0284c7;color:white;padding:4px 8px;margin:4px;border-radius:4px;display:inline-block;">${'$'}{w}</span>`
    ).join("");
  }
</script>

# app2.py
def extract_keywords(text):
    words = [w.lower().strip(".,!?:") for w in text.split()]
    stopwords = {"the", "a", "an", "is", "in", "it", "of", "and", "to", "for"}
    filtered = [w for w in words if len(w) > 3 and w not in stopwords]
    freq = {}
    for w in filtered: freq[w] = freq.get(w, 0) + 1
    return sorted(freq.keys(), key=lambda k: freq[k], reverse=True)[:5]
            """.trimIndent()
        ),

        // 20. Bridge: Telegram Bot Alert Integration Example (Educational Code Only)
        DocExample(
            id = "ex_20",
            title = "20. Bridge: Telegram Bot Alert Example (Educational)",
            category = "Backend Bridge",
            summary = "Educational code showing how telebot.TeleBot interacts with the bridge in custom projects.",
            explanation = """
NOTE: This is an educational code example demonstrating how users can author bot scripts in their own projects.
Runner is an Android project maker/runner, NOT a Telegram client.

In user projects using pyTelegramBotAPI:
1. Store bot token securely in bot_token.txt
2. Initialize telebot.TeleBot(token) in app2.py
3. Expose def send_alert(chat_id, text)
4. Invoke via window.RunnerBackend.call("send_alert", chatId, text)
            """.trimIndent(),
            codeSnippet = """
# app2.py (Educational Example Code)
import telebot

token_file = open("bot_token.txt", "r")
BOT_TOKEN = token_file.read().strip()
token_file.close()

bot = telebot.TeleBot(BOT_TOKEN)

def send_alert(chat_id, text):
    return bot.send_message(chat_id, text)

<!-- JavaScript (frontend): -->
// window.RunnerBackend.call("send_alert", targetChatId, messageText);
            """.trimIndent()
        ),

        // 21. Bridge: File Downloader & Hash Verifier
        DocExample(
            id = "ex_21",
            title = "21. Bridge: Hash Verifier",
            category = "Backend Bridge",
            summary = "Compute SHA-256 checksums of strings or project assets in Python.",
            explanation = "Shows how Python's hashlib library can be used to generate cryptographic hashes for the frontend.",
            codeSnippet = """
<!-- index.html -->
<input id="rawText" placeholder="Enter text to hash">
<button onclick="hashText()">Generate SHA-256</button>
<p id="hashOut" style="font-family:monospace;"></p>
<script>
  function hashText() {
    const str = document.getElementById("rawText").value;
    const sha = window.RunnerBackend.call("compute_sha256", str);
    document.getElementById("hashOut").innerText = sha;
  }
</script>

# app2.py
import hashlib
def compute_sha256(data):
    return hashlib.sha256(data.encode("utf-8")).hexdigest()
            """.trimIndent()
        ),

        // 22. Declarative runner.json
        DocExample(
            id = "ex_22",
            title = "22. Declarative runner.json Configuration",
            category = "Overview & Guides",
            summary = "Explicit project configuration file for custom entry points.",
            explanation = "Drop runner.json into the root of any project folder to override automatic detection.",
            codeSnippet = """
// runner.json
{
  "name": "Custom Dashboard App",
  "version": "1.0.0",
  "type": "html",
  "entry": "index.html",
  "backend": "app2.py",
  "orientation": "portrait"
}
            """.trimIndent()
        )
    )
}
